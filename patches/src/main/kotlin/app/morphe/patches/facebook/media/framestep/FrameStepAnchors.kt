/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.framestep

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.media.taptoplay.calls
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.reels.hold.playerCallees
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Where Frame step hooks, found by kept names only (read from 582, 2026-10-11). The obfuscated
 * names in these comments are for reviewers; the code never writes one down.
 *
 * The player side is FbGrootPlayer (582 LX/58B;), found as Resume long videos finds it: its kept
 * maybeTrackVideoStart and maybeTrackVideoStop, its release, its static seek holding
 * "FbGrootPlayer.seekToWithSafeSeek" (A0P) and the public seek handing on to it (Ejo), the
 * interface declaring isPlaying() and its one position reader. Two more:
 *
 * - The check that drops a seek too close to where the video is (A14). The static seek asks it,
 *   with the position as an Integer and the place asked for, and returns without seeking on a yes.
 *   It's the player's one instance method answering a boolean for an Integer and an int that the
 *   static seek calls. It answers yes when a MobileConfig flag or the player's state says to and
 *   the two are closer than a MobileConfig int, which a step of one frame can be.
 * - The view the player is attached to (A04), its one ViewGroup field the static attach holding
 *   "FbGrootPlayer.attachPlayerViewInternal" writes. The detach and the release clear it. On 585 the
 *   attach (05gS.A0R, v7a 05gY.A0R) no longer writes it itself: it hands the work to the player's
 *   static A0Q, which writes A06, so the player's methods the attach calls directly are read too.
 *
 * The tap side is GestureReactionComponent (582 LX/8Hb;), the Litho component that lays a gesture
 * view over nearly every reel and video player, which Turn off double tap to like finds by its
 * constructor's name. Its onCreateMountContent makes the view (582 LX/AKK;, a FrameLayout), and
 * the view's constructor hands setOnTouchListener the one OnTouchListener it makes (582 LX/BLV;,
 * holding the view's GestureDetector). That listener's onTouch sees every touch that reaches the
 * video and passes it to the detector last, so a tap's up turned into a cancel there is no tap to
 * Facebook.
 *
 * The step seeks with BY_INITIAL_FRAME_PERFECT_SEEK. The static seek asks a per-trigger check (582
 * A0l) whether to seek fast, and the service player then seeks with its own tolerant parameters
 * (582 `5CA.A0f`, two seconds before) instead of exact ones (`5CB.A03`, 0 and 0). That check
 * answers yes for BY_USER (ordinal 3) unless the video's params say no, and on the S22 a BY_USER
 * step on a paused reel landed on a keyframe up to two seconds back, or two seconds early when none
 * was there. It answers no for the frame-perfect trigger (ordinal 183), by name and by default.
 */

internal const val PATCH = "Frame step"

internal const val FRAME_STEP = "$EXTENSION_PACKAGE/media/FrameStep;"
internal const val STARTED = "$FRAME_STEP->started(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val STOPPED = "$FRAME_STEP->stopped(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val RELEASED = "$FRAME_STEP->released(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val SEEKING = "$FRAME_STEP->seeking(Ljava/lang/Object;Ljava/lang/Object;I)V"
internal const val STEPPING = "$FRAME_STEP->stepping()Z"
internal const val TOUCH = "$FRAME_STEP->touch(Landroid/view/View;Landroid/view/MotionEvent;)V"

/** The extension's stubs the patch fills in. */
internal const val POSITION_STUB = "position"
internal const val PLAYING_STUB = "playing"
internal const val VIEW_STUB = "playerView"
internal const val SEEK_STUB = "seekPlayer"

internal const val GROOT_ATTACH = "FbGrootPlayer.attachPlayerViewInternal"
internal const val VIEW_GROUP = "Landroid/view/ViewGroup;"
internal const val INTEGER = "Ljava/lang/Integer;"
internal const val ON_TOUCH_LISTENER = "Landroid/view/View\$OnTouchListener;"
internal const val VIEW = "Landroid/view/View;"
private const val SET_ON_TOUCH_LISTENER = "setOnTouchListener"

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.parameters() = parameterTypes.map(CharSequence::toString)
private fun Method.code() = implementation?.instructions?.toList().orEmpty()

/**
 * [owner]'s checks that drop a seek too close to where the video is: methods with a body answering a
 * boolean for an Integer and an int, which [safeSeek] calls. Up to 586 the check is an instance
 * method; 587 made it static (05MK.A0t), taking the player first.
 */
internal fun smallSeekChecks(owner: ClassDef, safeSeek: Method): List<Method> = owner.methods.filter {
    val arguments = if (it.isStatic()) listOf(owner.type, INTEGER, "I") else listOf(INTEGER, "I")
    it.returnType == "Z" && it.parameters() == arguments && it.implementation != null && calls(safeSeek, it)
}

/** [owner]'s attaches: static methods holding [GROOT_ATTACH]. */
internal fun grootAttaches(owner: ClassDef): List<Method> = owner.methods.filter {
    it.isStatic() && holdsString(it, GROOT_ATTACH)
}

/**
 * The ViewGroup fields of [owner] itself that [attach] writes, each once however often it's written.
 * 585 moved the write out of the attach into the one static [owner] method the attach hands the work
 * to, so the attach's own body and the [owner] methods it calls directly are both read.
 */
internal fun playerViewFields(owner: ClassDef, attach: Method): List<FieldReference> = (listOf(attach) + playerCallees(owner, attach))
    .flatMap { it.code() }
    .filter { it.opcode == Opcode.IPUT_OBJECT }
    .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
    .filter { it.definingClass == owner.type && it.type == VIEW_GROUP }
    .distinctBy { it.name }
    .filter { field -> owner.fields.any { it.name == field.name && it.type == VIEW_GROUP && !AccessFlags.STATIC.isSet(it.accessFlags) } }

/**
 * The types [view]'s constructors make that one of them hands to setOnTouchListener: every type a
 * constructor calling setOnTouchListener makes with new-instance. Which of them is the listener is
 * for the caller to tell, by the interfaces each implements.
 */
internal fun touchListenerCandidates(view: ClassDef): List<String> = view.methods
    .filter { it.name == "<init>" }
    .filter { constructor ->
        constructor.code().any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                call.name == SET_ON_TOUCH_LISTENER && call.parameterTypes.map(CharSequence::toString) == listOf(ON_TOUCH_LISTENER)
            } == true
        }
    }
    .flatMap { constructor ->
        constructor.code().filter { it.opcode == Opcode.NEW_INSTANCE }.map { (it as ReferenceInstruction).reference.toString() }
    }
    .distinct()

/** [listener]'s onTouch(View, MotionEvent), with a body. */
internal fun onTouches(listener: ClassDef): List<Method> = listener.methods.filter {
    it.name == "onTouch" && !it.isStatic() && it.returnType == "Z" && it.parameters() == listOf(VIEW, MOTION_EVENT) &&
        it.implementation != null
}
