/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.analytics

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Holds back Facebook's own analytics uploads: the XAnalytics event uploader and the Papaya
 * on-device learning jobs. See AnalyticsUploadAnchors.kt for where each hook goes. A second switch
 * holds back the camera roll cloud processing and its media count reports (CameraRollAnchors.kt).
 *
 * Every anchor has to be found once: a half-applied patch would leave one sender running while the
 * switch says it's held, so a missing one stops the patch naming all of them.
 *
 * In the default selection with its switch off: it trades Facebook's record of how the app is used
 * for less background traffic, which is a choice to make, not a fix.
 */
@Suppress("unused")
val holdAnalyticsUploadsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Hold back analytics uploads",
    description = "Stops Facebook sending its usage reports in the background and skips the learning jobs it " +
        "runs on your phone, so less about how you use the app leaves it. A second switch holds back the camera " +
        "roll processing that uploads your photos and details about them for sharing suggestions. Both start off. " +
        "Turn them on in Hushfacebook settings > Privacy, then restart Facebook.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val missing = mutableListOf<String>()
        fun <T> one(what: String, matches: List<T>): T? {
            if (matches.size == 1) return matches.single()
            missing += "$what (found ${matches.size})"
            return null
        }
        val jobStart = one(
            "the Papaya job's start",
            superclassChain(PAPAYA_SERVICE).mapNotNull { classDefByOrNull(it) }
                .flatMap { it.methods.filter(::isJobStart) }.take(1).toList(),
        )
        val gate = jobStart?.let { papayaGate(it) { type -> classDefByOrNull(type) } }
        if (jobStart != null && gate == null) missing += "the Papaya gate in ${jobStart.definingClass}->onStartJob"
        val kickOff = one(
            "XAnalytics' foreground upload",
            classDefByOrNull(APP_JOB_HANDLER)?.let { kickOffUploads(it) { type -> classDefByOrNull(type) } }.orEmpty(),
        )
        val resume = one("XAnalytics' uploader resume", classDefByOrNull(LOW_PRIORITY_INIT)?.let(::resumeUploads).orEmpty())
        val processing = one(
            "the camera roll processing check",
            classDefByOrNull(RUN_PIPELINE)?.let { run ->
                processingCheck(run) { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
            }?.let { call ->
                classDefByOrNull(call.definingClass)?.methods?.filter { isProcessingCheck(it, call) }
            }.orEmpty(),
        )
        val mediaCount = one(
            "the media count job's kill switch",
            classDefByOrNull(MEDIA_COUNT_JOB)?.methods?.mapNotNull { method ->
                mediaCountSwitch(method) { type -> classDefByOrNull(type) }?.let { method to it }
            }.orEmpty(),
        )
        if (missing.isNotEmpty()) {
            throw PatchException(
                "$PATCH: could not find " + missing.joinToString("; ") +
                    ". Applying the rest would leave that sender running, so nothing was changed.",
            )
        }
        val table = { call: MethodReference -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
        val retryOutcomes = classDefByStrings(ANALYTICS2_JOB_RAN, StringComparisonType.EQUALS).flatMap { owner ->
            owner.methods.mapNotNull { method -> analytics2RetryValue(method, table)?.let { method to it } }
        }
        if (retryOutcomes.size == 1) {
            val (method, index) = retryOutcomes.single()
            mutable(method).observeAnalytics2Retry(index, table)
        } else {
            patchLog.warning("$PATCH: found ${retryOutcomes.size} Analytics2 retry stores. Debug reports go on without retry outcomes.")
        }
        mutable(jobStart!!).passPapayaGate(gate!!)
        mutable(kickOff!!.first).skipUploadWhenHeld(kickOff.second, KICK_OFF_UPLOAD)
        mutable(resume!!.first).skipUploadWhenHeld(resume.second, RESUME_UPLOADING)
        mutable(processing!!).holdProcessingFirst()
        mutable(mediaCount!!.first).passMediaCountSwitch(mediaCount.second)
        enableStatus("analyticsUploads")
    }
}

/** The mutable copy of [method], matched on its name, return type and parameters' descriptors. */
private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }
