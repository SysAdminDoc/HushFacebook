/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.sellerprofile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.cloneMutableAndPreserveParameters
import app.morphe.util.executeBytecode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

internal const val PATCH = "Show View profile on Marketplace sellers"

/**
 * Facebook's config module for React Native. JavaScript calls its methods by name, so the class
 * and its methods keep their names in every build.
 */
internal const val CONFIG_MODULE = "Lcom/facebook/catalyst/modules/mobileconfignative/MobileConfigNativeModule;"

/** The module's reads of a boolean flag by its name, with and without Facebook's exposure logging. */
internal val NAMED_BOOLEAN_READS = setOf("getBool", "getBoolWithoutLogging")

/**
 * The module's reads of a boolean flag by its stable id instead, a config key and a param key handed
 * over as doubles, again with and without logging, and the contextual ones with an id after the two.
 */
internal val BY_ID_BOOLEAN_READS = setOf(
    "getBoolForStableSpec", "getBoolWithoutLoggingForStableSpec",
    "getBoolWithContextualId", "getBoolWithoutLoggingWithContextualId",
)

private const val STRING = "Ljava/lang/String;"
internal const val SELLER_PROFILE = "$EXTENSION_PACKAGE/navigation/MarketplaceSellerProfile;"
internal const val ANSWER_TRUE = "$SELLER_PROFILE->answerTrue($STRING)Z"
internal const val ANSWER_TRUE_FOR_SPEC = "$SELLER_PROFILE->answerTrueForSpec(DD)Z"

/** Whether [method] is one of the module's [NAMED_BOOLEAN_READS]: an instance (String)Z with a body. */
internal fun isNamedBooleanRead(method: Method): Boolean =
    method.name in NAMED_BOOLEAN_READS && method.returnType == "Z" &&
        method.parameterTypes.map { it.toString() } == listOf(STRING) &&
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null

/**
 * Whether [method] is one of the module's [BY_ID_BOOLEAN_READS]: an instance (DD)Z, or (DD String)Z
 * for the contextual ones, with a body.
 */
internal fun isByIdBooleanRead(method: Method): Boolean {
    if (method.name !in BY_ID_BOOLEAN_READS || method.returnType != "Z") return false
    val expected = if (method.name.endsWith("WithContextualId")) listOf("D", "D", STRING) else listOf("D", "D")
    return method.parameterTypes.map { it.toString() } == expected &&
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null
}

/**
 * Gives every seller's Marketplace page Facebook's own View profile button. The page shows it only
 * while an experiment flag reads true for the account, and reads that flag by name through the
 * config module's getBool or getBoolWithoutLogging. The extension's MarketplaceSellerProfile is
 * asked first in both, and answers true for that one flag while its switch is on; every other read
 * goes to Facebook's store as before. The module can read the same flag by its stable id too
 * (config key 49154, param key 15 in each build's rn_params.txt), so the four by-id reads ask the
 * extension first the same way, in case a bundle reads it like that.
 *
 * Included in the default selection: it only brings back a button Facebook gives other accounts,
 * and a seller's regular profile is how people check who they're buying from (#68).
 */
@Suppress("unused")
val showSellerViewProfilePatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Show View profile on Marketplace sellers",
    description = "Adds View profile to every seller's Marketplace page. It opens their regular Facebook " +
        "profile, so you can check who you're buying from. Facebook shows that button to only some accounts. On " +
        "by default. Turn it off in Hushfacebook settings > Marketplace.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val module = mutableClassDefByOrNull(CONFIG_MODULE)
            ?: throw PatchException("$PATCH: this build has no $CONFIG_MODULE")
        val reads = module.methods.filter(::isNamedBooleanRead)
        if (reads.map { it.name }.toSet() != NAMED_BOOLEAN_READS || reads.size != NAMED_BOOLEAN_READS.size) {
            throw PatchException("$PATCH: expected $NAMED_BOOLEAN_READS as (String)Z on $CONFIG_MODULE, found ${reads.map { it.name }}")
        }
        val byId = module.methods.filter(::isByIdBooleanRead)
        if (byId.map { it.name }.toSet() != BY_ID_BOOLEAN_READS || byId.size != BY_ID_BOOLEAN_READS.size) {
            throw PatchException("$PATCH: expected $BY_ID_BOOLEAN_READS as (DD)Z or (DD String)Z on $CONFIG_MODULE, found ${byId.map { it.name }}")
        }
        // The named reads are checked before any is changed, so a build missing room in one is left
        // as it was. The by-id ones can have no local at all (581's getBoolForStableSpec is all
        // parameters), so each is replaced by a copy with its parameters moved down, which frees
        // the registers they were in until the copy's first moves fill them.
        reads.forEach { it.checkRoomForTheAnswer() }
        reads.forEach { it.answerTrueFirst() }
        byId.forEach { it.cloneMutableAndPreserveParameters(module).answerTrueForSpecFirst() }
        enableStatus("sellerViewProfile")
    }
}

/** Throws unless the hook's one local and the name, in a four-bit register, fit this read. */
internal fun Method.checkRoomForTheAnswer() {
    val locals = localRegisterCount()
    // p1, the name, goes in a four-bit register field, so it has to sit at v15 or below.
    if (locals < 1 || locals + 1 > 15) {
        throw PatchException("$PATCH: $definingClass->$name has $locals locals, the hook needs 1 to 14")
    }
}

/**
 * Asks [ANSWER_TRUE] first thing and, on yes, returns true without reading Facebook's store. Uses
 * one local, which nothing has written yet, and the name, which is still in its register.
 */
internal fun MutableMethod.answerTrueFirst() {
    checkRoomForTheAnswer()
    addInstructionsWithLabels(
        0,
        """
            invoke-static { p1 }, $ANSWER_TRUE
            move-result v0
            if-eqz v0, :read
            return v0
        """,
        ExternalLabel("read", getInstruction(0)),
    )
}

/**
 * Asks [ANSWER_TRUE_FOR_SPEC] first thing with the config and param keys in p1 to p4 and, on yes,
 * returns true without reading Facebook's store. Runs on a copy made by
 * cloneMutableAndPreserveParameters, before the moves that put the parameters back where the
 * original code reads them, so v0 is still free for the answer.
 */
internal fun MutableMethod.answerTrueForSpecFirst() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p4 }, $ANSWER_TRUE_FOR_SPEC
            move-result v0
            if-eqz v0, :read
            return v0
        """,
        ExternalLabel("read", getInstruction(0)),
    )
}
