/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where Facebook decides whether the account may use its Facebook Plus app icons, on 582.
 *
 * Every icon ships in the APK as a disabled activity-alias of the launcher, and the App icon page
 * applies one by switching the aliases on the phone, with no server call. Two checks gate it, both
 * reading the account's synced subscription benefits:
 *
 * - The benefit provider (it logs as SUBSBenefitDataProvider and keeps the benefits under
 *   subs_active_benefits) answers whether a benefit name is in the set. The App icon page asks it
 *   for [BENEFIT] as it opens, and an app job asks it at each start and puts the default icon back
 *   when it says no. Its one `(String)Z` instance method that calls Set.contains is the check, and
 *   the hook goes first in it.
 * - The App icon page also re-reads each synced set itself: a `(Set)V` callback that loads
 *   [BENEFIT], calls Set.contains with it on the set and keeps the answer as the page's unlocked
 *   flag. The hook passes that answer through the extension right after its move-result.
 */

internal const val PATCH = "Unlock app icons"

/** The benefit name Facebook gives its paid app icons. */
internal const val BENEFIT = "CUSTOM_APP_ICON"

/** The name the benefit provider logs under, and the key it keeps the benefits under. */
internal const val PROVIDER_TAG = "SUBSBenefitDataProvider"
internal const val PROVIDER_KEY = "subs_active_benefits"

private const val SET = "Ljava/util/Set;"
private const val SET_CONTAINS = "$SET->contains(Ljava/lang/Object;)Z"

private const val APP_ICONS = "$EXTENSION_PACKAGE/misc/AppIcons;"
internal const val UNLOCKED = "$APP_ICONS->unlocked(Ljava/lang/String;)Z"
internal const val ENTITLED = "$APP_ICONS->entitled(Z)Z"

private fun MethodReference.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun callsSetContains(instruction: Instruction): Boolean =
    instruction.opcode == Opcode.INVOKE_INTERFACE &&
        ((instruction as ReferenceInstruction).reference as? MethodReference)?.signature() == SET_CONTAINS

/** Whether [classDef] is the benefit provider: a method loads [PROVIDER_TAG] and one loads [PROVIDER_KEY]. */
internal fun isBenefitProvider(classDef: ClassDef): Boolean =
    classDef.methods.any { holdsString(it, PROVIDER_TAG) } && classDef.methods.any { holdsString(it, PROVIDER_KEY) }

/** Whether [method] is the provider's check of one benefit: an instance `(String)Z` that calls Set.contains. */
internal fun isBenefitCheck(method: Method): Boolean =
    method.returnType == "Z" && method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;") &&
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.implementation?.instructions?.any(::callsSetContains) == true

/**
 * The index of the move-result that takes the App icon page's own look for [BENEFIT] in [method], or
 * null when [method] isn't that `(Set)V` callback: a const-string of [BENEFIT], then at once
 * Set.contains on the set parameter with that register, then its move-result.
 */
internal fun pickerBenefitLook(method: Method): Int? {
    if (method.returnType != "V" || method.parameterTypes.map(Any::toString) != listOf(SET)) return null
    val implementation = method.implementation ?: return null
    val code = implementation.instructions.toList()
    // The set is the last parameter register; an instance method's p0 comes before it.
    val set = implementation.registerCount - 1
    val found = code.indices.filter { index ->
        val load = code[index]
        val name = ((load as? ReferenceInstruction)?.reference as? StringReference)?.string
        if ((load.opcode != Opcode.CONST_STRING && load.opcode != Opcode.CONST_STRING_JUMBO) || name != BENEFIT) return@filter false
        val call = code.getOrNull(index + 1) ?: return@filter false
        val loaded = (load as OneRegisterInstruction).registerA
        callsSetContains(call) && (call as FiveRegisterInstruction).registerCount == 2 &&
            call.registerC == set && call.registerD == loaded && code.getOrNull(index + 2)?.opcode == Opcode.MOVE_RESULT
    }
    return found.singleOrNull()?.let { it + 2 }
}

/** First in the benefit check: [UNLOCKED] answers yes for the benefit asked, and the check answers true at once. */
internal fun MutableMethod.unlockFirst() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { p1 }, $UNLOCKED
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("check", getInstruction(0)),
    )
}

/** Passes the App icon page's answer, taken by the move-result at [index], through [ENTITLED]. */
internal fun MutableMethod.passPickerBenefit(index: Int) {
    val answer = (getInstruction(index) as? OneRegisterInstruction)?.takeIf { getInstruction(index).opcode == Opcode.MOVE_RESULT }
        ?.registerA ?: throw PatchException("$PATCH: instruction $index of $definingClass->$name isn't the benefit look's move-result")
    addInstructions(
        index + 1,
        """
            invoke-static/range { v$answer .. v$answer }, $ENTITLED
            move-result v$answer
        """,
    )
}
