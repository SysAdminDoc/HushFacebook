/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.watchhistory

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/*
 * The two other ways Facebook records the videos you watch, on every 585 build (#408).
 *
 * The seen-state batches only come from the Reels viewer, so a feed video, a story or a reel played
 * outside the viewer still lands in Reels you've watched. Two senders cover the rest:
 *
 * - SfdVideoPositionWatchedMutation, a GraphQL mutation taking `video_id` and `position_ms`. A
 *   player's state listener reports the furthest point it reached in a video as it pauses or ends,
 *   and a static method sends it once that point is 10 seconds in or more, keeping the furthest
 *   point sent for each video. The same method counts the videos for Facebook's short-video ads.
 * - The VOD/Reels view-time logger, one static method the `android_update_video_view_time`,
 *   `vpv_event_created` and `vpv_event_attach_to_query` events all go through. It logs the video's
 *   id, how long it was watched, the feed query it came from and its ranking payloads.
 *
 * Each is a static void method no one waits on, found by the literals it loads, so the hook asks
 * the extension at its first instruction and returns before anything is built.
 */

/** The position mutation's name, loaded by the method that sends it. */
internal const val POSITION_MUTATION = "SfdVideoPositionWatchedMutation"

/** The position mutation's input field, loaded by the same method. */
internal const val POSITION_MS = "position_ms"

/** A field only the view-time logger writes. */
internal const val VIEW_TIME_POSITION = "client_vdd_position"

/** The logger's watch time field. */
internal const val VIEW_TIME = "vvt"

/** The extension's answer for the position mutation: true holds it back. */
internal const val HOLD_POSITION = "$EXTENSION_PACKAGE/reels/ReelWatchHistory;->holdWatchedPosition()Z"

/** The extension's answer for a view-time event: true holds it back. */
internal const val HOLD_VIEW_TIME = "$EXTENSION_PACKAGE/reels/ReelWatchHistory;->holdViewTime()Z"

/** Whether [method] is a static void method loading both [first] and [second]. */
private fun staticSender(method: Method, first: String, second: String): Boolean =
    method.returnType == "V" && AccessFlags.STATIC.isSet(method.accessFlags) &&
        holdsString(method, first) && holdsString(method, second)

/** Whether [method] sends the watched-position mutation. */
internal fun positionSend(method: Method): Boolean = staticSender(method, POSITION_MUTATION, POSITION_MS)

/** Whether [method] is the VOD/Reels view-time logger. */
internal fun viewTimeLog(method: Method): Boolean = staticSender(method, VIEW_TIME_POSITION, VIEW_TIME)

/**
 * Puts the question [hold] in front of the method's first instruction: on a yes the method returns
 * at once. It borrows v0, which holds nothing before the method's own first instruction runs, and
 * the label binds to that instruction, so nothing else moves.
 */
internal fun MutableMethod.holdAtStart(hold: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        "invoke-static { }, $hold\nmove-result v0\nif-eqz v0, :send\nreturn-void",
        ExternalLabel("send", getInstruction(0)),
    )
}
