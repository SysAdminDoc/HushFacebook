/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesponsoredstories/Fingerprints.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.ads.sponsoredstories

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
internal const val JAVA_LIST = "Ljava/util/List;"

internal const val INORGANIC_CLASH = "handling_inorganic_clash"
internal const val UNINSERTED_ADS = "uninsertedMainAdsQueue"
internal const val PAGINATING_BUCKETS = "AdPaginatingBucketStaticInsertionDataSource.getBuckets"
internal const val MID_CARD_BUCKETS = "StoryViewerMidCardDataSource.getBuckets"

/** A bucket data source's interleave method: it takes the organic list last and returns a list. */
internal fun isBucketSource(method: Method) =
    method.returnType == IMMUTABLE_LIST && method.parameterTypes.lastOrNull()?.toString() == IMMUTABLE_LIST

/** Whether [method] calls [placement]. */
internal fun handsOffTo(method: Method, placement: Method): Boolean {
    val signature = placement.parameterTypes.map(CharSequence::toString)
    return method.implementation?.instructions?.any { instruction ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.definingClass == placement.definingClass && called.name == placement.name &&
            called.returnType == placement.returnType && called.parameterTypes.map(CharSequence::toString) == signature
    } ?: false
}

/**
 * Story buckets do not pass through one inserter. `StoryviewerBucketDataController.processBucketData`
 * holds an `ImmutableList` of bucket data sources and calls the same interleave method on each,
 * feeding every result into the next. The chain is assembled per session behind a launch-config
 * predicate and MobileConfig gates, so which sources are present varies by account.
 *
 * Every source below takes the organic list and returns it with its own buckets spliced in, and all
 * four share one signature: `(FbUserSession, <flags>, ImmutableList) -> ImmutableList`. Returning the
 * input unchanged is the result Facebook itself produces when a source contributes nothing.
 *
 * Three are anchored on a string literal that is unique in the APK, and the fourth on the helper it
 * calls. The class and method names are Redex names that change every release, so none is named here.
 */

/**
 * The clash resolver, which decides the order when two ad ("inorganic") buckets land together.
 * Anchored on `"handling_inorganic_clash"`.
 */
internal object InorganicClashResolverFingerprint : Fingerprint(
    returnType = IMMUTABLE_LIST,
    strings = listOf(INORGANIC_CLASH),
)

/**
 * The placement step of `AdBucketDataSourceUtil`, the main ad placement engine and by far the largest
 * of the four sources. It keeps queues of inserted and pending ads alongside a model of the organic
 * stream, then decides where each ad lands. Since 588 that work sits in a helper over plain lists, so
 * the source itself holds no string and is found as this helper's caller (`adBucketDataSource`).
 *
 * `"uninsertedMainAdsQueue"` also appears in the placement-rule helper, so the return type is what
 * selects this method: the rule helper's two methods return `void` and `boolean`.
 */
internal object AdBucketPlacementFingerprint : Fingerprint(
    returnType = JAVA_LIST,
    strings = listOf(UNINSERTED_ADS),
)

/**
 * `AdPaginatingBucketStaticInsertionDataSource` — drains a queue of pending ad buckets as the viewer
 * paginates. A factory picks this or the main engine by flag, so both ship in the APK and both need
 * neutering.
 */
internal object AdPaginatingBucketInserterFingerprint : Fingerprint(
    returnType = IMMUTABLE_LIST,
    strings = listOf(PAGINATING_BUCKETS),
)

/**
 * `StoryViewerMidCardDataSource` — mid-card ads fetched while the viewer is already open, which is
 * why they appear mid-session and are gone after the app restarts.
 */
internal object StoryViewerMidCardFingerprint : Fingerprint(
    returnType = IMMUTABLE_LIST,
    strings = listOf(MID_CARD_BUCKETS),
)
