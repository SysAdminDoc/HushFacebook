/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.findMutableMethodOf

/**
 * The screen class the full-screen viewers and pages like Saved (ImmersiveActivity) come down from.
 * Its touch dispatch hands the event straight to its delegate and never runs FbFragmentActivity's.
 */
internal const val DELEGATING_ACTIVITY = "Lcom/facebook/base/activity/DelegatingFbFragmentFrameworkActivity;"

internal const val SAVED_CHAIN_TOUCH =
    "$EXTENSION_PACKAGE/reels/SavedChain;->touched(Landroid/app/Activity;$MOTION_EVENT)V"

/** Both screen bases whose touch dispatch the extension reads first. */
internal val SAVED_TOUCH_SCREENS = listOf(DELEGATING_ACTIVITY, FRAGMENT_ACTIVITY)

/**
 * Play saved videos in a row (#404): the extension reads every touch first thing in the touch
 * dispatch of FbFragmentActivity and of DelegatingFbFragmentFrameworkActivity, the base of the Saved
 * page and the viewer it opens (ImmersiveActivity, Facebook 585), whose own dispatch skips
 * FbFragmentActivity's. It keeps the card a press lands on and only reads the event, through the
 * range form. The rest, the viewer opening from Saved, a reel ending and a swipe, comes through the
 * reels viewer entry and Reel binding hooks already there.
 */
internal fun BytecodePatchContext.observeSavedTouches() {
    for (type in SAVED_TOUCH_SCREENS) {
        val screen = classDefByOrNull(type) ?: throw PatchException("this build has no $type")
        val dispatch = touchDispatches(screen).singleOrNull()
            ?: throw PatchException("expected $type to declare one dispatchTouchEvent($MOTION_EVENT)Z")
        mutableClassDefBy(type).findMutableMethodOf(dispatch)
            .addInstruction(0, "invoke-static/range { p0 .. p1 }, $SAVED_CHAIN_TOUCH")
    }
}
