/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.carrierbanners

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PATCH = "Hide carrier data banners"

/** The two log steps Facebook's banner method writes as it starts and once it has shown a banner. */
internal const val SWITCHER_START = "maybe_show_dialtone_manual_switcher_start"
internal const val SWITCHER_END = "maybe_show_dialtone_manual_switcher_end"

/** The log step right after the method hides the banner because its carrier's banner isn't turned on. */
internal const val NOT_ENABLED = "on_autoflex_banner_not_enabled"

internal const val HIDES = "Lapp/morphe/extension/facebook/misc/CarrierBanners;->hides(Ljava/lang/String;)Z"

private const val SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val STRING = "Ljava/lang/String;"

/**
 * Facebook Flex's banner method: it picks the carrier banner by Facebook's mode and server answers,
 * sets it up and shows it. Found by the two log steps only it writes.
 */
internal object CarrierBannerFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(SWITCHER_START, SWITCHER_END),
)

/**
 * Where the banner method meets after every banner it shows, and what it needs there.
 *
 * @property keyRead the read of the banner's key, the String field each branch sets, into
 * [keyRegister] from the manager in [manager], right before the first call that takes it.
 * @property hide the manager's own ()V method the banner method calls to hide the banner.
 * @property exit the monitor-exit before the method's return, where a hidden banner leaves.
 */
internal class BannerJoin(
    val keyRead: Int,
    val keyRegister: Int,
    val manager: Int,
    val keyField: FieldReference,
    val hide: MethodReference,
    val exit: Int,
)

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/**
 * Reads [method], Facebook's banner method, for its join point. Every name is found by shape: the
 * hide call is the manager's ()V call right before [NOT_ENABLED] is logged; the join point is the
 * first read of one of the manager's String fields that goes straight into a static call of the
 * manager's class taking (FbUserSession, banner, manager, String).
 */
internal fun bannerJoin(method: Method): BannerJoin {
    val owner = method.definingClass
    val code = method.implementation?.instructions?.toList() ?: throw PatchException("$PATCH: the banner method has no code")
    val logged = code.indexOfFirst { it.string() == NOT_ENABLED }
    if (logged < 1) throw PatchException("$PATCH: the banner method doesn't log $NOT_ENABLED")
    val hideCall = code[logged - 1]
    val hide = (hideCall as? ReferenceInstruction)?.reference as? MethodReference
    if (hideCall.opcode != Opcode.INVOKE_VIRTUAL || hide == null || hide.definingClass != owner ||
        hide.parameterTypes.isNotEmpty() || hide.returnType != "V"
    ) {
        throw PatchException("$PATCH: $NOT_ENABLED isn't logged right after the banner is hidden")
    }
    val manager = (hideCall as FiveRegisterInstruction).registerC

    val keyRead = code.indices.firstOrNull { index ->
        val read = code[index]
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference
        val call = code.getOrNull(index + 1)
        val callee = (call as? ReferenceInstruction)?.reference as? MethodReference
        read.opcode == Opcode.IGET_OBJECT && field != null && field.definingClass == owner && field.type == STRING &&
            (read as TwoRegisterInstruction).registerB == manager &&
            call?.opcode == Opcode.INVOKE_STATIC && callee != null && callee.definingClass == owner &&
            callee.returnType == "V" && callee.parameterTypes.size == 4 &&
            callee.parameterTypes[0].toString() == SESSION && callee.parameterTypes[2].toString() == owner &&
            callee.parameterTypes[3].toString() == STRING &&
            (call as? FiveRegisterInstruction)?.registerF == read.registerA
    } ?: throw PatchException("$PATCH: no point where the banner method hands on the banner's key")
    val read = code[keyRead] as TwoRegisterInstruction

    val returns = code.indexOfLast { it.opcode == Opcode.RETURN_VOID }
    val exit = returns - 1
    if (returns < 1 || code[exit].opcode != Opcode.MONITOR_EXIT || (code[exit] as OneRegisterInstruction).registerA != manager) {
        throw PatchException("$PATCH: the banner method doesn't let go of its manager before it returns")
    }
    return BannerJoin(keyRead, read.registerA, manager, (code[keyRead] as ReferenceInstruction).reference as FieldReference, hide, exit)
}

/**
 * Right after the banner's key is read at the join point, before the method logs the banner and
 * adds its tooltips, the key goes to [HIDES]. A yes hides the banner with Facebook's own hide call
 * and leaves through the method's exit, the way its branches for no banner do; a no reads the key
 * again, since the answer took its register, and the method goes on as it was. Every register the
 * snippet names is one the method's own read and call use there, so each fits its instruction.
 */
internal fun BytecodePatchContext.hookCarrierBanners() {
    val method = CarrierBannerFingerprint.methodOrNull ?: throw PatchException("$PATCH: Facebook Flex's banner method isn't in this build")
    val join = bannerJoin(method)
    val key = join.keyRegister
    method.addInstructionsWithLabels(
        join.keyRead + 1,
        """
            invoke-static/range { v$key .. v$key }, $HIDES
            move-result v$key
            if-eqz v$key, :keep
            invoke-virtual/range { v${join.manager} .. v${join.manager} }, ${join.hide}
            goto :leave
            :keep
            iget-object v$key, v${join.manager}, ${join.keyField}
        """,
        ExternalLabel("leave", method.getInstruction(join.exit)),
    )
}

@Suppress("unused")
val hideCarrierBannersPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hide carrier data banners",
    description = "On carriers with Facebook Flex, hides the Data mode bar with its buy mobile data button, or the " +
        "purple Free mode bar, so the feed gets that space back. Both switches start off. Turn them on in " +
        "Hushfacebook settings > Appearance.",
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        hookCarrierBanners()
        enableStatus("carrierBanners")
    }
}
