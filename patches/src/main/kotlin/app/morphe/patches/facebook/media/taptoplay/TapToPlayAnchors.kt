/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.taptoplay

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Where Tap to play hooks, found by kept names only (read from 577 and 580, 2026-09-27). The
 * obfuscated names in these comments are for reviewers; the code never writes one down.
 *
 * - FbGrootPlayer's play, which nearly every surface's start reaches: the feed's inline videos,
 *   Watch, Reels, the story viewer, songs on photo posts and notes, Marketplace. It's the one
 *   method holding the trace "FbGrootPlayer.play", taking the start's EventTriggerType (580
 *   LX/5BR;->EJb(LX/2cl;)V, 577 LX/4qS;->EH1(LX/2mO;)V). Its pauses hold "FbGrootPlayer.pause":
 *   the one the others hand on to takes the trigger and a second argument (580 A1h, 577 A1g), and
 *   the other is the one-argument pause (580 EIL, 577 EFn). The video is bound in the one method
 *   holding "FbGrootPlayer.bindVideoSources" (580 A1l, 577 A1j), which the play itself can reach.
 * - The older Rich Video Player's playback controller, whose play logs "Play requested with video
 *   surface [%s]" and takes the session and the trigger (580 LX/UNt;->A0D, 577 LX/UYe;->A0D), and
 *   whose pause logs "Pause requested with video surface [%s]" (580 EIL, 577 EFn). The Rich Video
 *   Player builds it only when a video doesn't go through Groot; it's gated too so nothing starts
 *   past the switch that way.
 * - EventTriggerType itself: an enum whose static initializer names BY_USER, BY_AUTOPLAY and the
 *   rest (580 LX/2cl;, 577 LX/2mO;). The extension reads a trigger by name(), never by field.
 * - Facebook's Autoplay setting: VideoAutoPlaySettingsChecker, whose constructor refuses a missing
 *   preference with a message naming it, answers the setting from its one no-argument method
 *   returning the enum of ON, OFF, WIFI_ONLY and DEFAULT (580 LX/3zA;->A08()LX/3zJ;, 577
 *   LX/3z3;->A08()LX/3zC;). The feed's autoplay manager, search results, Marketplace's own views,
 *   the story tray's tiles, the Reels tray in the feed and the Reels and Watch controls all read it.
 * - Taps: FbFragmentActivity.dispatchTouchEvent, a framework override every Facebook screen
 *   inherits. The story viewer's floating screen overrides it and hands on to it. Double tap to
 *   skip on feed videos sees each touch there first, then the tap clock (#432).
 * - The Reels controls' check of whether a reel starts with autoplay off: the one method the
 *   classes holding "FbShortsVideoControlComponent" call that takes the session, the viewer's
 *   config and two booleans and answers a boolean (580 LX/88V;->A07, 577 LX/9bD;->A07), and asks
 *   the Autoplay settings checker. Its callers are that component's initial state and its reset
 *   (580 LX/83J;->A1M and A1J, 577 LX/7mG;->A1S and A1R): a yes puts the control in
 *   AUTOPLAY_OFF_INIT_STATE, where the reel shows its play button, whose tap ("reels_play_button_click")
 *   goes through "unpause" to FbGrootPlayer's play with BY_USER. A yes changes nothing else: the
 *   control's state change only tells the player's event bus.
 * - The reel check: FbGrootPlayer's one getter of its VideoPlayerParams, and the boolean the params'
 *   debug dump reports as isFbShorts, the same two Keep the reel speed reads. 581's Reels tab starts
 *   each reel it lands on with BY_AUTOPLAY, which feed videos send too, so the gate asks the params
 *   (read from 581 on a phone, 2026-10-07).
 */

internal const val TAP_TO_PLAY = "$EXTENSION_PACKAGE/media/TapToPlay;"
internal const val TAP_CLOCK = "$EXTENSION_PACKAGE/media/TapClock;"
internal const val ALLOW_START = "$TAP_TO_PLAY->allowStart(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val ALLOW_LEGACY_START = "$TAP_TO_PLAY->allowLegacyStart(Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val PAUSED = "$TAP_TO_PLAY->paused(Ljava/lang/Object;)V"
internal const val REBOUND = "$TAP_TO_PLAY->rebound(Ljava/lang/Object;)V"
internal const val AUTOPLAY_SETTING = "$TAP_TO_PLAY->autoplaySetting(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val TOUCH = "$TAP_CLOCK->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)V"
internal const val DOUBLE_TAP_TOUCH =
    "$EXTENSION_PACKAGE/reels/FeedVideoDoubleTap;->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)V"
internal const val SHOW_REEL_PLAY_BUTTON = "$TAP_TO_PLAY->showReelPlayButton(Z)Z"

/** The extension's reel check stubs the patch fills, and the name the params' dump reports the flag under. */
internal const val PLAYER_PARAMS_STUB = "playerParams"
internal const val FB_SHORTS_STUB = "fbShorts"
internal const val FB_SHORTS = "isFbShorts"

internal const val GROOT_PLAY = "FbGrootPlayer.play"
internal const val GROOT_PAUSE = "FbGrootPlayer.pause"
internal const val GROOT_BIND = "FbGrootPlayer.bindVideoSources"
internal const val LEGACY_PLAY = "Play requested with video surface [%s]"
internal const val LEGACY_PAUSE = "Pause requested with video surface [%s]"
internal const val AUTOPLAY_SETTINGS_CHECKER =
    "Shared preference is expected to be present in VideoAutoPlaySettingsChecker but was null"

internal const val REELS_CONTROLS = "FbShortsVideoControlComponent"
internal const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

internal const val FRAGMENT_ACTIVITY = "Lcom/facebook/base/activity/FbFragmentActivity;"
internal const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val ENUM = "Ljava/lang/Enum;"

/**
 * The trigger names the extension's rule reads: the ones it lets through without a tap, the ones
 * it never lets a tap start, and the two a tap starts most often. A build that renamed one would
 * change the rule without a word, so the patch stops instead.
 */
internal val TRIGGER_NAMES = listOf(
    "BY_USER", "BY_AUTOPLAY", "BY_MEDIA_SESSION_CONTROLS", "BY_SEEKBAR_CONTROLLER", "BY_MUSIC_PLAYER",
    "BY_SHORT_FORM_VIDEO_FULLY_VISIBLE", "BY_SHORT_FORM_VIDEO_ONRESUME", "BY_SURFACE_ON_RESUME",
    "BY_FRAGMENT_RESUME", "BY_FLYOUT",
)

/** The Autoplay setting's values; the extension answers the one named OFF. */
internal val SETTING_NAMES = listOf("ON", "OFF", "WIFI_ONLY", "DEFAULT")

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

/** Whether [classDef] is an enum whose static initializer names every one of [names]. */
internal fun isEnumNaming(classDef: ClassDef, names: List<String>): Boolean =
    classDef.superclass == ENUM && classDef.methods.any { method ->
        method.name == "<clinit>" && names.all { holdsString(method, it) }
    }

/** FbGrootPlayer's play in [owner]: holds the play trace, returns nothing and takes one argument. */
internal fun grootPlays(owner: ClassDef): List<Method> = owner.methods.filter {
    !it.isStatic() && it.returnType == "V" && it.parameterTypes.size == 1 && holdsString(it, GROOT_PLAY)
}

/** [owner]'s pauses that take [trigger] first and hold the pause trace. */
internal fun grootPauses(owner: ClassDef, trigger: String): List<Method> = owner.methods.filter {
    !it.isStatic() && it.returnType == "V" && it.parameters().firstOrNull() == trigger && holdsString(it, GROOT_PAUSE)
}

/**
 * The pause every one of [pauses] goes through: the one that calls none of the others, when each
 * of the others calls it. Null when there isn't exactly one such.
 */
internal fun innerPause(pauses: List<Method>): Method? {
    val inner = pauses.filter { pause -> pauses.none { other -> other !== pause && calls(pause, other) } }
        .singleOrNull() ?: return null
    return inner.takeIf { pauses.all { it === inner || calls(it, inner) } }
}

/** [owner]'s binds: hold the bind trace, return nothing and take one argument. */
internal fun grootBinds(owner: ClassDef): List<Method> = owner.methods.filter {
    !it.isStatic() && it.returnType == "V" && it.parameterTypes.size == 1 && holdsString(it, GROOT_BIND)
}

/** The older player's play in [owner]: holds its log line, returns nothing, takes [trigger] once. */
internal fun legacyPlays(owner: ClassDef, trigger: String): List<Method> = owner.methods.filter {
    !it.isStatic() && it.returnType == "V" && it.parameters().count { type -> type == trigger } == 1 &&
        holdsString(it, LEGACY_PLAY)
}

/** The older player's pause in [owner]: holds its log line, returns nothing and takes only [trigger]. */
internal fun legacyPauses(owner: ClassDef, trigger: String): List<Method> = owner.methods.filter {
    !it.isStatic() && it.returnType == "V" && it.parameters() == listOf(trigger) && holdsString(it, LEGACY_PAUSE)
}

/** Whether [owner] is VideoAutoPlaySettingsChecker: a constructor of it holds the refusal naming it. */
internal fun isAutoplaySettingsChecker(owner: ClassDef): Boolean =
    owner.methods.any { it.name == "<init>" && holdsString(it, AUTOPLAY_SETTINGS_CHECKER) }

/**
 * The checker's reader: an instance method with no arguments whose answer [isSettingEnum] says is
 * the setting's enum.
 */
internal fun settingReaders(owner: ClassDef, isSettingEnum: (String) -> Boolean): List<Method> = owner.methods.filter {
    !it.isStatic() && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") && isSettingEnum(it.returnType) &&
        it.implementation != null
}

/** FbFragmentActivity's own touch dispatch, with a body to go first in. */
internal fun touchDispatches(activity: ClassDef): List<Method> = activity.methods.filter {
    it.name == "dispatchTouchEvent" && it.returnType == "Z" && it.parameters() == listOf(MOTION_EVENT) &&
        !it.isStatic() && it.implementation != null
}

/** The touch hooks already first in [method]: the calls it opens with that go to a touch hook. */
internal fun touchHooksAtStart(method: Method): Set<String> =
    method.implementation?.instructions?.take(2).orEmpty().mapNotNull { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
            .takeIf { it == TOUCH || it == DOUBLE_TAP_TOUCH }
    }.toSet()

/** Whether [caller] invokes [callee]. */
internal fun calls(caller: Method, callee: Method): Boolean =
    caller.implementation?.instructions?.any { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        reference.definingClass == callee.definingClass && reference.name == callee.name &&
            reference.returnType == callee.returnType &&
            reference.parameterTypes.map(CharSequence::toString) == callee.parameters()
    } == true

/** Whether a method of these parameters and answer is shaped like the Reels autoplay-off check. */
internal fun isAutoplayOffCheckShape(parameters: List<String>, returnType: String): Boolean =
    returnType == "Z" && parameters.size == 4 && parameters[0] == FB_USER_SESSION && parameters[1].startsWith("L") &&
        parameters[2] == "Z" && parameters[3] == "Z"

/** The methods of that shape [component]'s methods call, as "class->name(parameters)answer", each once. */
internal fun autoplayOffChecksCalled(component: ClassDef): Set<String> = component.methods.flatMap { method ->
    method.implementation?.instructions?.mapNotNull { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        val parameters = reference.parameterTypes.map(CharSequence::toString)
        if (!isAutoplayOffCheckShape(parameters, reference.returnType)) return@mapNotNull null
        "${reference.definingClass}->${reference.name}(${parameters.joinToString("")})${reference.returnType}"
    }.orEmpty()
}.toSet()

/** The method of [owner] a signature from [autoplayOffChecksCalled] names, when it has a body. */
internal fun methodNamed(owner: ClassDef, signature: String): Method? = owner.methods.singleOrNull {
    "${it.definingClass}->${it.name}(${it.parameters().joinToString("")})${it.returnType}" == signature &&
        it.implementation != null && !it.isStatic()
}

/** Whether [method] asks [checker] something with the session, as the Reels check asks the Autoplay setting. */
internal fun asksWithSession(method: Method, checker: String): Boolean =
    method.implementation?.instructions?.any { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        reference.definingClass == checker && reference.parameterTypes.firstOrNull()?.toString() == FB_USER_SESSION
    } == true
