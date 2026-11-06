/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.pinch

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

internal const val PATCH = "Pinch to zoom"
internal const val PINCH_ZOOM = "Lapp/morphe/extension/facebook/media/PinchZoom;"
internal const val PINCH_TOUCH = "$PINCH_ZOOM->touch(Landroid/app/Activity;$MOTION_EVENT)Z"
internal const val PINCH_PROFILE = "$PINCH_ZOOM->profile(Landroid/view/View;)V"

/** The profile screen's fragment, a kept class name, in the same activity as the news feed. */
internal const val PROFILE_FRAGMENT = "Lcom/facebook/timeline/fragment/ProfileFragment;"

/** ProfileFragment's own onViewCreated(View, Bundle), which gets the profile's root view first. */
internal fun profileViewCreated(fragment: ClassDef): List<Method> = fragment.methods.filter {
    it.name == "onViewCreated" && it.returnType == "V" &&
        it.parameterTypes.map { type -> type.toString() } == listOf("Landroid/view/View;", "Landroid/os/Bundle;") &&
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
}

/**
 * Every touch on a Facebook screen goes to the extension's PinchZoom first, from
 * FbFragmentActivity.dispatchTouchEvent, the framework override every Facebook screen shares. The
 * extension zooms a copy of the photo or story under two fingers and turns the rest of that
 * gesture into a cancel for Facebook, or in the story viewer keeps it from Facebook until the zoom
 * ends: a yes from it returns true before Facebook sees the event. Everything else it leaves as it
 * was. The news feed has its own pinch for a post's photos, so the extension zooms only in the story
 * viewer, inside a profile, whose root view ProfileFragment.onViewCreated hands it, on a feed video
 * whose player Tap to play knows, and on a photo in Photo carousel's row.
 */
@Suppress("unused")
val pinchToZoomPatch = bytecodePatch(
    name = "Pinch to zoom",
    description = "Pinch a story, a video in your feed, or a photo on someone's profile or in Photo carousel to zoom " +
        "in, like you can on other photos in the feed. It goes back when you let go, unless you turn on Stay zoomed " +
        "under it, which keeps the zoom until you tap or press Back. Keep videos playing while zoomed, another switch " +
        "under it, lets a video play on while you zoom, stories included, and with Stay zoomed you press and hold to " +
        "pause it. Starts off. Turn it on in Hushfacebook settings > Stories.",
    default = true,
) {
    category("Stories")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val activity = classDefByOrNull(FRAGMENT_ACTIVITY)
            ?: throw PatchException("$PATCH: this build has no $FRAGMENT_ACTIVITY")
        val dispatches = touchDispatches(activity)
        val dispatch = dispatches.singleOrNull() ?: throw PatchException(
            "$PATCH: expected $FRAGMENT_ACTIVITY to declare one dispatchTouchEvent($MOTION_EVENT)Z, found ${dispatches.size}",
        )
        val fragment = classDefByOrNull(PROFILE_FRAGMENT)
            ?: throw PatchException("$PATCH: this build has no $PROFILE_FRAGMENT")
        val created = profileViewCreated(fragment).singleOrNull() ?: throw PatchException(
            "$PATCH: expected $PROFILE_FRAGMENT to declare one onViewCreated(View, Bundle)",
        )
        requireStatusMethod("pinchToZoom")
        // The screen and the event are p0 and p1, next to each other, so the range form passes both.
        // The answer borrows v0, which holds nothing before the method's own first instruction.
        val touches = mutableClassDefBy(FRAGMENT_ACTIVITY).findMutableMethodOf(dispatch)
        touches.requireLocals(PATCH, 1)
        touches.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p1 }, $PINCH_TOUCH
                move-result v0
                if-eqz v0, :facebook
                return v0
            """.trimIndent(),
            ExternalLabel("facebook", touches.getInstruction(0)),
        )
        mutableClassDefBy(PROFILE_FRAGMENT).findMutableMethodOf(created)
            .addInstruction(0, "invoke-static/range { p1 .. p1 }, $PINCH_PROFILE")
        enableStatus("pinchToZoom")
    }
}
