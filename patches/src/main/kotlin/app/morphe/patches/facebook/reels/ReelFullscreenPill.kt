/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/*
 * The Fullscreen pill under a wide video in the Reels viewer (#394). Read from 585, 2026-11-06,
 * arm64; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - The pill is a Litho component of its own (LX/0CNt;). Its click handler logs the tap as
 *   "full_screen_pill", the only place that literal appears in the app, and the viewer builds the
 *   component in one place, for a video wider than it is tall.
 * - Its render(ComponentContext) answers null on its own when the phone is sideways, so the pill
 *   isn't drawn over a video that's already full screen. The hook answers that same null first
 *   thing, and the space under the video stays black, as it is in landscape.
 *
 * The full-screen and picture-in-picture buttons on a video that plays in the feed or in search
 * results, a Live that has ended among them (#478). Read from 587, 2026-11-14, arm64.
 *
 * - Each of a feed video's buttons is a plugin with a descriptor class of its own (LX/05pV; for
 *   the full-screen arrows, LX/0Pnr; and LX/0Pns; for the two picture-in-picture buttons). Each
 *   names itself through a kept literal, "InlineFullscreenTogglePluginDescriptor",
 *   "PopoutButtonPluginDescriptor" and "VodPopoutButtonPluginDescriptor", which only that class
 *   holds.
 * - Each has an include check (LX/05pV;->Hf9, taking the player type, the PlayerOrigin and two
 *   more) that the player's renderer (LX/05ZZ;->A19) asks before it adds the button, and it adds
 *   it only when the answer is true. Search results set HideFullscreenButtonKey on their own player
 *   (LX/0Pc4;) for some videos, which the full-screen check reads, so Facebook already leaves the
 *   button off there sometimes.
 * - The hook answers false first thing in all three, so the renderer leaves the buttons out and
 *   the video plays as before. Off, paused or failing, Facebook's own check answers.
 */

internal const val HIDE_FULLSCREEN_PILL = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideFullscreenPill()Z"

internal const val HIDE_FEED_VIDEO_FULLSCREEN = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideFeedVideoFullscreen()Z"
internal const val HIDE_FEED_VIDEO_PIP = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideFeedVideoPip()Z"

/** The name the feed video's full-screen button's descriptor gives itself. */
internal const val INLINE_FULLSCREEN_TOGGLE = "InlineFullscreenTogglePluginDescriptor"

/** The names the two picture-in-picture buttons' descriptors give themselves. */
internal val POPOUT_BUTTONS = listOf("PopoutButtonPluginDescriptor", "VodPopoutButtonPluginDescriptor")

/** What the pill's click handler logs the tap as, a literal no other class holds. */
internal const val FULL_SCREEN_PILL = "full_screen_pill"

private const val PATCH = "Clean up Reels"

/** The render of [classDef] when it's a component with one, else null. */
internal fun fullscreenPillRender(classDef: ClassDef): Method? = classDef.methods.singleOrNull {
    it.name == "render" && it.parameterTypes.size == 1 && it.returnType.startsWith("L") && it.implementation != null
}

/** The pill's render asks the extension first thing and answers null when told to. */
internal fun BytecodePatchContext.hideFullscreenPill() {
    val owners = classDefByStrings(FULL_SCREEN_PILL, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .filter { fullscreenPillRender(it) != null }
    val owner = owners.singleOrNull()
        ?: throw PatchException("$PATCH: expected one component holding \"$FULL_SCREEN_PILL\" with a render, found ${owners.size}")
    mutableClassDefBy(owner.type).methods.single {
        it.name == "render" && it.parameterTypes.size == 1 && it.returnType.startsWith("L") && it.implementation != null
    }.returnNullWhen(HIDE_FULLSCREEN_PILL)
}

/** The include check of a plugin descriptor: its one instance method that takes a PlayerOrigin and answers a boolean. */
internal fun includeCheck(classDef: ClassDef): Method? = classDef.methods.filter(::isIncludeCheck).singleOrNull()

private fun isIncludeCheck(method: Method) =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.implementation != null &&
        method.parameterTypes.any { it.toString() == PLAYER_ORIGIN }

/**
 * The feed video's full-screen button and both picture-in-picture buttons ask the extension first in
 * their include checks and answer false when told to. All three are found before any is changed.
 */
internal fun BytecodePatchContext.hideFeedVideoButtons() {
    val targets = (listOf(INLINE_FULLSCREEN_TOGGLE to HIDE_FEED_VIDEO_FULLSCREEN) + POPOUT_BUTTONS.map { it to HIDE_FEED_VIDEO_PIP })
        .map { (name, hook) ->
            val owners = classDefByStrings(name, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                .filter { includeCheck(it) != null }
            val owner = owners.singleOrNull()
                ?: throw PatchException("$PATCH: expected one plugin descriptor named \"$name\" with an include check, found ${owners.size}")
            owner.type to hook
        }
    targets.forEach { (type, hook) ->
        mutableClassDefBy(type).methods.single(::isIncludeCheck).returnFalseWhen(hook)
    }
}
