/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.location

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.updates.CONTEXTUAL_FILTER
import app.morphe.patches.facebook.updates.PROMOTION_DEFINITION
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/*
 * Facebook's location page, read from 587 on 2026-11-17 (#498). The obfuscated names are for
 * reviewers; the code finds the filter by its parameter types (kept class names) and its strings.
 *
 * The page "To use Location Services, allow Facebook to access your location", with Skip and
 * Continue, is a Quick Promotion the server sends for the login interstitial trigger
 * (FACEBOOK_FOR_ANDROID_LOGIN_INTERSTITIAL_QP, promotion 662953529497730, template
 * consent_flow_interstitial, drawn by QuickPromotionLoginInterstitialBloksActivity). It shows only
 * while its contextual filters pass, and one of them, nxx_location_service_client, is answered on
 * the phone: ContextualFilter$Type's NXX_LOCATION_SERVICE_CLIENT maps (LX/098h's ordinal switch) to
 * LX/098o, whose A04(FbUserSession, InterstitialTrigger, ContextualFilter, QuickPromotionDefinition)
 * passes while Facebook hasn't been given location and the count it keeps under "location_service"
 * is under its cap. The same method checks "pass_word_change", and no other method holds both.
 *
 * The first build of this patch (e8248c429) closed DeviceLocationSettingsOptInActivity, a different
 * screen behind fb://device_location_optin. The reported page never goes through it.
 *
 * The hook goes first in the filter: the trigger goes to the extension, and a yes answers false, so
 * the promotion fails its filter and Facebook moves on to Home as if it had never been sent.
 */

internal const val PATCH = "Skip location prompts"

/** The count Facebook keeps for the location promotion, and the key only its filter reads beside it. */
internal const val LOCATION_COUNT = "location_service"
internal const val PASSWORD_CHANGE = "pass_word_change"

private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
private const val TRIGGER = "Lcom/facebook/interstitial/triggers/InterstitialTrigger;"

internal const val SKIPS = "$EXTENSION_PACKAGE/misc/LocationPrompts;->skips(Ljava/lang/Object;)Z"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The nxx_location_service_client filter: an instance method answering a boolean for (session,
 * trigger, filter, promotion) that holds both [LOCATION_COUNT] and [PASSWORD_CHANGE].
 */
internal fun isLocationPromotionFilter(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags).not() &&
        method.returnType == "Z" &&
        method.parameterTypes.map { it.toString() } == listOf(USER_SESSION, TRIGGER, CONTEXTUAL_FILTER, PROMOTION_DEFINITION) &&
        holdsString(method, LOCATION_COUNT) &&
        holdsString(method, PASSWORD_CHANGE)

/** The one location promotion filter in this build. It refuses when there's none or more than one. */
internal fun BytecodePatchContext.locationPromotionFilter(): Method {
    val filters = classDefByStrings(PASSWORD_CHANGE, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { owner -> owner.methods.filter(::isLocationPromotionFilter) }
    return filters.singleOrNull()
        ?: refuse("expected one location promotion filter, found ${filters.map { "${it.definingClass}->${it.name}" }}")
}

/**
 * First in the filter, hands the trigger to [SKIPS]. A yes answers false so the promotion doesn't
 * show; a no goes on to Facebook's own check.
 */
internal fun BytecodePatchContext.skipLocationPromotion() {
    val filter = locationPromotionFilter()
    val method = mutableClassDefBy(filter.definingClass).findMutableMethodOf(filter)
    method.requireLocals(PATCH, 1)
    // Parameters sit at the top: this, session, trigger, filter, promotion.
    val trigger = method.implementation!!.registerCount - 3
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { v$trigger .. v$trigger }, $SKIPS
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", method.getInstruction(0)),
    )
}

@Suppress("unused")
val skipLocationPromptsPatch = patch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Skip location prompts",
    description = "Stops Facebook from showing its page asking you to allow Location Services, the one with Skip " +
        "and Continue that comes up after you sign in. On by default. Turn it off in Hushfacebook settings > Privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        skipLocationPromotion()
        enableStatus("locationPrompts")
    }
}
