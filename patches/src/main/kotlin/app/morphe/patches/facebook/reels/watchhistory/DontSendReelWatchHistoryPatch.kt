/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.watchhistory

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.analytics.analyticsEventsPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Holds back the batches of watched reels Facebook's Reels viewer sends for its own ranking. See
 * SeenStateSend.kt for what the batch is and where the hook goes.
 *
 * The hook takes the place of the flush's hand-over to its executor, after the queue has been
 * emptied and the mutation built, and before anything reaches the GraphQL layer, so a batch held
 * back is gone rather than kept for a later send. The extension hands the
 * runnable to the executor as Facebook would unless the switch is on and the runnable is the one
 * Redex named as the seen-state send.
 *
 * In the default selection with its switch off: it trades Facebook's record of what you've
 * watched for reels you've seen coming back, which is a choice to make, not a fix.
 */
/** Puts [hold] at the start of the one method holding [literal] that [isSender] picks. */
private fun BytecodePatchContext.holdSender(literal: String, isSender: (Method) -> Boolean, hold: String) {
    val senders = classDefByStrings(literal, StringComparisonType.EQUALS).flatMap { owner -> owner.methods.filter(isSender) }
    val sender = senders.singleOrNull()
        ?: throw PatchException("$PATCH: expected one static void method holding \"$literal\", found ${senders.size}")
    mutableClassDefBy(sender.definingClass).methods.single {
        it.name == sender.name && it.returnType == sender.returnType &&
            it.parameterTypes.map(Any::toString) == sender.parameterTypes.map(Any::toString)
    }.holdAtStart(hold)
}

@Suppress("unused")
val dontSendReelWatchHistoryPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Don't send reel watch history",
    description = "Stops sending Facebook the reels and videos you've watched, where you stopped in a video and how " +
        "long you watched it, so they stay out of Videos you've watched in your activity log and Facebook learns less " +
        "about what you watch. Videos you've already seen may come back. Starts off. Turn it on in Hushfacebook " +
        "settings > Reels and Watch.",
) {
    category("Privacy")
    dependsOn(settingsPatch, analyticsEventsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val points = classDefByStrings(SEEN_STATE_MUTATION, StringComparisonType.EQUALS).flatMap { owner ->
            owner.methods.mapNotNull { method -> sendPoint(method) { classDefByOrNull(it) }?.let { method to it } }
        }
        val (flush, index) = points.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one method holding \"$SEEN_STATE_MUTATION\" and filling \"$VIDEO_IDS\" that hands the runnable " +
                "Redex names $SEEN_STATE_SEND to an Executor after naming the mutation, found ${points.size}",
        )
        mutableClassDefBy(flush.definingClass).methods.single {
            it.name == flush.name && it.returnType == flush.returnType &&
                it.parameterTypes.map(Any::toString) == flush.parameterTypes.map(Any::toString)
        }.withholdSendAt(index)

        // #408: the position mutation and the view-time logger record the videos watched outside
        // the Reels viewer. See WatchedVideoSends.kt. "Videos you've watched" in the activity log is
        // filled from the players' media_playback_compound analytics events instead, which
        // analyticsEventsPatch asks the extension about.
        holdSender(POSITION_MUTATION, ::positionSend, HOLD_POSITION)
        holdSender(VIEW_TIME_POSITION, ::viewTimeLog, HOLD_VIEW_TIME)
        enableStatus("reelWatchHistory")
    }
}
