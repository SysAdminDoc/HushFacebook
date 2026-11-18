/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/**
 * The query a profile's or Page's Reels tab sends for the ads it puts between that profile's reels.
 * It doesn't draw from the Reels and Watch ad pool [POOL_NO_AD] holds, so the pool's hold doesn't
 * reach it. Up to 587 one method built and sent it (581 `LX/B5t;->A03`, read 2026-10-06), a void
 * instance method taking the session, two Integers and a boolean, which returns early on its own
 * when the ads it already holds fill the tab's slots. 588 moves the building into a static helper
 * that answers the request (arm64 `LX/09k7;->A00`), and two void methods send what it builds: that
 * same instance method, and a static one for the tab's newer fetch (arm64 `LX/0Cgx;->A02`), which
 * sends it on its own and only falls back on the first.
 */
internal const val PROFILE_REELS_ADS = "ProfileReelsAsyncAdsQuery"

private const val HOLD_PROFILE_REEL_ADS = "$EXTENSION_PACKAGE/ads/ReelsAdFilter;->holdProfileReelAds()Z"

/** The query's builder: a method with a body that answers something and names [PROFILE_REELS_ADS]. */
internal fun isProfileReelAdBuilder(method: Method): Boolean =
    method.returnType != "V" && holdsString(method, PROFILE_REELS_ADS)

/** Whether [method] calls [builder]. */
internal fun callsProfileReelAdBuilder(method: Method, builder: Method): Boolean =
    method.implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
            call.definingClass == builder.definingClass && MethodUtil.methodSignaturesMatch(call, builder)
        } == true
    } == true

/**
 * Every method that sends [PROFILE_REELS_ADS]: the callers of the one method building it. Each has to
 * return nothing, since holding the query means returning before it goes out.
 */
internal fun BytecodePatchContext.profileReelAdFetches(): List<MutableMethod> {
    val holding = classDefByStrings(PROFILE_REELS_ADS, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { holder -> holder.methods.filter { holdsString(it, PROFILE_REELS_ADS) } }
    val builder = holding.singleOrNull()?.takeIf(::isProfileReelAdBuilder) ?: throw PatchException(
        "$SPONSORED_REELS_PATCH: expected one method building \"$PROFILE_REELS_ADS\", found " +
            holding.joinToString { "${it.definingClass}->${it.name}${it.parameterTypes}${it.returnType}" }.ifEmpty { "none" },
    )
    val senders = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(builder.definingClass)) { owner ->
        if (owner.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        owner.methods.filterTo(senders) { callsProfileReelAdBuilder(it, builder) }
    }
    if (senders.isEmpty() || senders.any { it.returnType != "V" }) {
        throw PatchException(
            "$SPONSORED_REELS_PATCH: expected void methods sending \"$PROFILE_REELS_ADS\", found " +
                senders.joinToString { "${it.definingClass}->${it.name}${it.returnType}" }.ifEmpty { "none" },
        )
    }
    return senders.map { mutableClassDefBy(it.definingClass).findMutableMethodOf(it) }
}

/**
 * First thing in a fetch: ask the extension, and return when it holds the query back. Returning
 * is what the fetch does itself when it has ads enough, so the tab goes on with the profile's own
 * reels and no query goes out.
 */
internal fun MutableMethod.holdProfileReelAdsFirst() {
    requireLocals(SPONSORED_REELS_PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_PROFILE_REEL_ADS
            move-result v0
            if-eqz v0, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}
