/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/blockadtelemetry/BlockAdTelemetryPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.ads.telemetry

import app.morphe.patches.facebook.shared.neuterOrReason
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.handleTargets
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.Method

private const val PATCH = "Block ad telemetry"

/** Ad measurement that runs whether or not an ad is shown. All keep their real names. */
internal val AD_TELEMETRY = listOf(
    // Watches for you taking a screenshot of an ad. The controller's void methods add and remove
    // the detector's listener, so neutering them means it is never registered. 586's detector
    // (com.facebook.ads.AdsScreenshotDetector) kept no void method to stop, so it left this list;
    // Block screenshot detection still reaches it through the shared screenshot observer.
    "Lcom/facebook/ads/screenshot/AdsScreenshotController;",
    // Ad attribution for apps you install: the tracker's scheduler and the service that does the
    // reporting (doHandleIntent keeps its name).
    "Lcom/facebook/feed/platformads/AppInstallTrackerScheduler;",
    "Lcom/facebook/feed/platformads/AppInstallService;",
)

/**
 * Jobs that report the advertising ID or check Android's ad measurement, mapped on 582 (2026-10-10).
 * Each keeps its name and has only void methods, so neutering stops the job outright:
 * LatStatusJob posts the ID with its limit-tracking flag to /attributions, PrivacySandboxCapabilities-
 * Checker logs Android's measurement status, and TestPACustomAudienceAppJob joins a test custom
 * audience on Android's ad services.
 */
internal val AD_ID_JOBS = listOf(
    "Lcom/facebook/attribution/LatStatusJob;",
    "Lcom/facebook/privacysandbox/PrivacySandboxCapabilitiesChecker;",
    "Lcom/facebook/privacysandbox/protectedaudience/TestPACustomAudienceAppJob;",
)

/**
 * Strings that each mark the one void method sending an identifier, which returns at once: the
 * advertising ID to Facebook as a GraphQL mutation, the advertising ID, App Manager id and device
 * id in a launch event, an ad view or click registered with Android's attribution, and the
 * advertising ID with the preinstall metadata in the first login screen's install event. The method
 * names are Redex's, so the strings find them.
 */
internal val AD_ID_SENDERS = listOf(
    "ReportAdvertiserIDMutation",
    "fb4a_launch_custom_data",
    "https://www.facebook.com/privacy_sandbox/mobile/register/source?tracking_data=",
    "app_new_install",
)

/**
 * The content provider other apps on the phone ask for the advertising ID (a kept name). Its query
 * logs the asking app's package as an inferred app launch, then answers with the ID, its limit
 * tracking flag and the Android ID. When Google Play services has no ID, Facebook answers one row
 * of nulls, which is what the query answers at once instead.
 */
internal const val ATTRIBUTION_PROVIDER = "Lcom/facebook/katana/provider/AttributionIdProvider\$Impl;"

private val QUERY_PARAMETERS =
    listOf("Landroid/net/Uri;", "[Ljava/lang/String;", "Ljava/lang/String;", "[Ljava/lang/String;", "Ljava/lang/String;")

private const val NO_AD_ID_ROW = """
    const-string v0, "aid"
    const-string v1, "androidid"
    const-string v2, "limit_tracking"
    filled-new-array { v0, v1, v2 }, [Ljava/lang/String;
    move-result-object v0
    new-instance v1, Landroid/database/MatrixCursor;
    invoke-direct { v1, v0 }, Landroid/database/MatrixCursor;-><init>([Ljava/lang/String;)V
    const/4 v0, 0x0
    filled-new-array { v0, v0, v0 }, [Ljava/lang/String;
    move-result-object v0
    invoke-virtual { v1, v0 }, Landroid/database/MatrixCursor;->addRow([Ljava/lang/Object;)V
    return-object v1
"""

@Suppress("unused")
val blockAdTelemetryPatch = bytecodePatch(
    name = "Block ad telemetry",
    description = "Stops Facebook watching for screenshots of ads and reporting which apps you install after " +
        "seeing ads, and stops several of the ways it sends your phone's advertising ID, so less of what you do " +
        "feeds its ad tracking. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    // Deliberately not covered:
    //
    // - AdVisualQualityEngine / OcrPreprocessor (on-device OCR of ad creative). Their only entry
    //   points are suspend functions returning Object, so an early return hands null to a
    //   continuation that does not expect one. They only run on a rendered ad anyway.
    // - PigeonFeedUnitSponsoredImpressionLogger. Its one clean entry point marks an impression as
    //   *already logged*; neutering it invites repeat logging rather than none.
    //
    // Each class stands alone, so a build that renamed some still gets the others stopped, and the
    // patch log names each one left running. None found stops the patch.
    execute {
        handleTargets(PATCH, "ad telemetry classes", AD_TELEMETRY) { neuterOrReason(it) }

        // The advertising ID reporting is extra: a build without one of these still gets the
        // classes above, so a missing one is an info line rather than a warning.
        AD_ID_JOBS.forEach { type -> neuterOrReason(type)?.let { patchLog.info("$PATCH: $it.") } }
        AD_ID_SENDERS.forEach { marker -> stopSenderOrReason(marker)?.let { patchLog.info("$PATCH: $it.") } }
        answerNoAdIdOrReason()?.let { patchLog.info("$PATCH: $it.") }

        enableStatus("adTelemetry")
    }
}

/** Whether [method] is a sender [marker] picks: a void method loading exactly that string. */
internal fun isAdIdSender(method: Method, marker: String) = method.returnType == "V" && holdsString(method, marker)

/** Null once the one void method holding exactly [marker] returns at once, or why nothing changed. */
internal fun BytecodePatchContext.stopSenderOrReason(marker: String): String? {
    val senders = classDefByStrings(marker, StringComparisonType.EQUALS).flatMap { classDef ->
        classDef.methods.filter { isAdIdSender(it, marker) }
    }
    val sender = senders.singleOrNull()
        ?: return "${senders.size} void methods hold \"$marker\", expected 1, so that send keeps running"
    val mutable = mutableClassDefBy(sender.definingClass).methods.filter {
        it.name == sender.name && it.returnType == sender.returnType &&
            it.parameterTypes.map(Any::toString) == sender.parameterTypes.map(Any::toString)
    }
    mutable.singleOrNull()?.returnEarly()
        ?: return "${sender.definingClass}->${sender.name} matched ${mutable.size} methods to change, so that send keeps running"
    return null
}

/** Whether [method] has ContentProvider.query's five-argument shape and a body. */
internal fun isProviderQuery(method: Method) = method.returnType == "Landroid/database/Cursor;" &&
    method.parameterTypes.map(Any::toString) == QUERY_PARAMETERS && method.implementation != null

/** Null once the attribution provider's query answers the no-ID row at once, or why nothing changed. */
internal fun BytecodePatchContext.answerNoAdIdOrReason(): String? {
    val queries = classDefByOrNull(ATTRIBUTION_PROVIDER)?.methods?.filter(::isProviderQuery)
        ?: return "this build has no $ATTRIBUTION_PROVIDER, so other apps can still ask it for the advertising ID"
    val query = queries.singleOrNull()
        ?: return "$ATTRIBUTION_PROVIDER has ${queries.size} query methods, expected 1, so other apps can still ask it for the advertising ID"
    if (query.localRegisterCount() < 3) return "$ATTRIBUTION_PROVIDER->${query.name} has under 3 locals, so other apps can still ask it for the advertising ID"
    mutableClassDefBy(ATTRIBUTION_PROVIDER).findMutableMethodOf(query).addInstructions(0, NO_AD_ID_ROW)
    return null
}
