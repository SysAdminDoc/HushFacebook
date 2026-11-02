/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.ads.sponsoredreels.dropItemFirst
import app.morphe.patches.facebook.ads.sponsoredreels.filterPageFirst
import app.morphe.patches.facebook.ads.sponsoredreels.filterSectionsFirst
import app.morphe.patches.facebook.ads.sponsoredreels.reelPages
import app.morphe.patches.facebook.ads.sponsoredreels.toBinaryName
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/*
 * Only reels from accounts you follow, on the Reels tab (#349). Read from 585, 2026-11-02.
 *
 * - Facebook keeps whether you follow someone as a GraphQL enum whose names it keeps: UNSET_OR_
 *   UNRECOGNIZED_ENUM_VALUE, CANNOT_SUBSCRIBE, CAN_SUBSCRIBE and IS_SUBSCRIBED ("subscribe" is
 *   Facebook's old word for follow). The enum is LX/02va; in both builds.
 * - One method answers it for a reel: an instance method taking the reel's model and a boolean
 *   (arm64 LX/08B6;->A0D(LX/0eTv;Z), armeabi-v7a LX/08BB;->A0D(LX/0eTc;Z)), called on the one
 *   instance its class keeps in a static field of its own type. The reel's Follow button reads it
 *   with false and follows when it answers CAN_SUBSCRIBE (LX/0eIy;->invoke, arm64).
 * - The patch fills the extension's stub with that call and puts the extension's filter on every
 *   way a page of reels enters the Reels list, as Hide AI-detected posts does with its own, with
 *   the model's class beside each. The extension drops a reel only when the answer is CAN_SUBSCRIBE.
 */

private const val FOLLOWED_REELS = "$EXTENSION_PACKAGE/reels/FollowedReels;"
internal const val FOLLOWED_REEL_FILTER =
    "$FOLLOWED_REELS->followedOnly(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;"
internal const val ANNOUNCED_FOLLOWED_REEL_FILTER =
    "$FOLLOWED_REELS->followedOnlyAnnounced(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;"
internal const val FOLLOWED_REEL_SECTION_FILTER =
    "$FOLLOWED_REELS->followedSections(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

/** The extension's stub the patch fills: `Object followStatus(Object model, Object unused, boolean fresh)`. */
internal const val FOLLOW_STATUS_STUB = "followStatus"
internal const val FOLLOW_STATUS_STUB_CLASS = FOLLOWED_REELS

/** The follow status enum's kept names: an author you can follow but don't, and one you follow. */
internal const val CAN_FOLLOW = "CAN_SUBSCRIBE"
internal const val FOLLOWED = "IS_SUBSCRIBED"

private const val PATCH = "Clean up Reels"
private const val ENUM = "Ljava/lang/Enum;"

/** The follow status call: [method] answers the enum for a reel's model, called on [instance], a static field of its class. */
internal class FollowStatus(val method: Method, val instance: String) {
    val model: String get() = method.parameterTypes[0].toString()
    val call: String get() = "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
}

/** The follow status enum: the one Enum whose static initializer names both [CAN_FOLLOW] and [FOLLOWED]. */
internal fun BytecodePatchContext.followStatusEnum(): String {
    val found = classDefByStrings(FOLLOWED, StringComparisonType.EQUALS)
        .filter { it.superclass == ENUM && it.methods.any { method -> method.name == "<clinit>" && holdsString(method, CAN_FOLLOW) } }
        .map { it.type }.distinct()
    return found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one enum naming $CAN_FOLLOW and $FOLLOWED, found ${found.size}")
}

/**
 * The one instance method outside the extension answering the follow status enum for a model and a
 * boolean, and the static field of its own type its class keeps the instance in.
 */
internal fun BytecodePatchContext.followStatus(): FollowStatus {
    val status = followStatusEnum()
    val found = mutableListOf<Method>()
    classDefForEachReferencing(types = listOf(status)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEachReferencing
        classDef.methods.filterTo(found) { method ->
            val parameters = method.parameterTypes.map(CharSequence::toString)
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == status && parameters.size == 2 &&
                parameters[0].startsWith("L") && !parameters[0].startsWith("Ljava/") && parameters[1] == "Z"
        }
    }
    val method = found.distinctBy { "${it.definingClass}->${it.name}" }.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method answering $status for a reel and a boolean, found ${found.size}",
    )
    val owner = classDefBy(method.definingClass)
    val instances = owner.staticFields.filter { it.type == owner.type }
    val instance = instances.singleOrNull()
        ?: throw PatchException("$PATCH: ${owner.type} keeps ${instances.size} instances of itself, expected one to call the follow status on")
    return FollowStatus(method, "${owner.type}->${instance.name}:${instance.type}")
}

/**
 * Fills the extension's stub with the follow status call. Only the stub's three parameter registers
 * are used, as [app.morphe.patches.facebook.feed.aidetected.fillFinderStub] uses only its two: the
 * instance goes in the one the extension passes null in, and the boolean is passed as it came.
 */
internal fun BytecodePatchContext.fillFollowStatusStub(status: FollowStatus) {
    val stub = mutableClassDefBy(FOLLOW_STATUS_STUB_CLASS).methods.singleOrNull {
        it.name == FOLLOW_STATUS_STUB && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Z")
    } ?: throw PatchException("$FOLLOW_STATUS_STUB_CLASS has no static Object $FOLLOW_STATUS_STUB(Object, Object, boolean)")
    stub.addInstructions(
        0,
        """
            check-cast p0, ${status.model}
            sget-object p1, ${status.instance}
            invoke-virtual { p1, p0, p2 }, ${status.call}
            move-result-object p0
            return-object p0
        """,
    )
}

/**
 * Puts the followed-only filter on every way a page of reels enters the Reels list, with the reel
 * model's class beside it so the extension can find the model each item keeps.
 */
internal fun BytecodePatchContext.keepFollowedReels() {
    val status = followStatus()
    fillFollowStatusStub(status)
    val pages = reelPages(PATCH)
    val model = status.model.toBinaryName()
    pages.pageInserts.forEach { it.filterPageFirst(model, FOLLOWED_REEL_FILTER, PATCH) }
    pages.announcePage.filterPageFirst(model, ANNOUNCED_FOLLOWED_REEL_FILTER, PATCH)
    pages.insertItem.dropItemFirst(model, FOLLOWED_REEL_FILTER, PATCH)
    pages.addPage.filterSectionsFirst(model, FOLLOWED_REEL_SECTION_FILTER, PATCH)
}
