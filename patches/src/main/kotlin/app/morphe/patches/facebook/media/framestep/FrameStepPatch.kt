/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.framestep

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.resume.IS_PLAYING
import app.morphe.patches.facebook.media.resume.TRACK_START
import app.morphe.patches.facebook.media.resume.TRACK_STOP
import app.morphe.patches.facebook.media.resume.playingInterfaces
import app.morphe.patches.facebook.media.resume.positionReaders
import app.morphe.patches.facebook.media.resume.releases
import app.morphe.patches.facebook.media.resume.safeSeeks
import app.morphe.patches.facebook.media.resume.seekTos
import app.morphe.patches.facebook.media.resume.trackers
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.reels.doubletap.GESTURE_REACTION
import app.morphe.patches.facebook.reels.doubletap.isGestureReactionComponent
import app.morphe.patches.facebook.reels.doubletap.mountedView
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** The trigger the extension's step seeks with, which the trigger enum has to name. */
internal const val STEP_TRIGGER = "BY_INITIAL_FRAME_PERFECT_SEEK"

/**
 * FbGrootPlayer's start, stop, release and seek tell the extension about themselves, its check that
 * drops a small seek asks the extension first, and the gesture view's touch listener hands the
 * extension each touch first. The extension's stubs are filled with the player's position reader,
 * isPlaying, attached view and seek. See FrameStepAnchors.kt for where each is, and the extension's
 * FrameStep for the rule.
 */
@Suppress("unused")
val frameStepPatch = patch(
    name = "Frame step",
    description = "With a reel or video paused, a tap on its right side steps one frame forward and a tap on its " +
        "left side one frame back, so you can stop on the exact moment you want. Starts off. Turn it on in " +
        "Hushfacebook settings > Video and audio.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val anchors = frameStepAnchors()
        hookPlayer(anchors)
        mutableClassDefBy(anchors.onTouch.definingClass).findMutableMethodOf(anchors.onTouch)
            .addInstruction(0, "invoke-static/range { p1 .. p2 }, $TOUCH")
        fillStubs(anchors)
        enableStatus("frameStep")
    }
}

/** What the patch found of FbGrootPlayer and the gesture view's touch listener. */
internal class FrameStepAnchors(
    val owner: ClassDef,
    val trigger: String,
    val start: Method,
    val stop: Method,
    val release: Method,
    val safeSeek: Method,
    val seekTo: Method,
    val smallSeek: Method,
    val position: Method,
    val isPlaying: Method,
    val view: FieldReference,
    val onTouch: Method,
)

/** Finds everything the patch needs, or stops naming what's missing, before anything changes. */
internal fun BytecodePatchContext.frameStepAnchors(): FrameStepAnchors {
    val plays = classDefByStrings(GROOT_PLAY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap(::grootPlays)
    val play = plays.singleOrNull()
        ?: throw PatchException("$PATCH: expected one player play holding \"$GROOT_PLAY\", found ${plays.size}")
    val owner = classDefBy(play.definingClass)
    val trigger = play.parameterTypes.single().toString()
    val triggerEnum = classDefByOrNull(trigger)
        ?: throw PatchException("$PATCH: the play's trigger type $trigger isn't in this build")
    if (!isEnumNaming(triggerEnum, listOf(STEP_TRIGGER))) {
        throw PatchException("$PATCH: the play's trigger type $trigger isn't an enum naming $STEP_TRIGGER")
    }

    fun single(what: String, found: List<Method>): Method = found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $what in ${owner.type}, found ${found.size}")

    val start = single("$TRACK_START($trigger)", trackers(owner, TRACK_START, trigger))
    val stop = single("$TRACK_STOP($trigger)", trackers(owner, TRACK_STOP, trigger))
    val release = single("release", releases(owner, trigger))
    val safeSeek = single("static seek", safeSeeks(owner, trigger))
    val seekTo = single("seek taking the trigger and an int", seekTos(owner, trigger, safeSeek))
    val smallSeek = single("small-seek check the static seek asks", smallSeekChecks(owner, safeSeek))
    if (smallSeek.localRegisterCount() < 1) {
        throw PatchException("$PATCH: ${owner.type}->${smallSeek.name} has no register of its own for the hook")
    }
    val isPlaying = single("$IS_PLAYING()", owner.methods.filter {
        it.name == IS_PLAYING && it.returnType == "Z" && it.parameterTypes.isEmpty() && it.implementation != null &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    })

    val interfaces = owner.interfaces.mapNotNull { classDefByOrNull(it) }
    val playing = playingInterfaces(interfaces).singleOrNull() ?: throw PatchException(
        "$PATCH: expected one interface of ${owner.type} declaring $IS_PLAYING(), found " +
            "${playingInterfaces(interfaces).size} of ${owner.interfaces.size}",
    )
    val position = single("int method of ${playing.type}", positionReaders(owner, playing))

    val attach = single("static attach holding \"$GROOT_ATTACH\"", grootAttaches(owner))
    val views = playerViewFields(owner, attach)
    val view = views.singleOrNull() ?: throw PatchException(
        "$PATCH: expected the attach to write one $VIEW_GROUP field of ${owner.type}, found ${views.map { it.name }}",
    )

    // The extension's stubs reach these from outside Facebook's package.
    listOf(owner, triggerEnum).forEach { reachable ->
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) {
            throw PatchException("$PATCH: ${reachable.type} isn't public, so the extension can't reach it")
        }
    }
    listOf(position, isPlaying, seekTo).forEach { method ->
        if (!AccessFlags.PUBLIC.isSet(method.accessFlags)) {
            throw PatchException("$PATCH: ${owner.type}->${method.name} isn't public, so the extension can't call it")
        }
    }
    if (owner.fields.none { it.name == view.name && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
        throw PatchException("$PATCH: ${owner.type}->${view.name} isn't public, so the extension can't read it")
    }

    val components = classDefByStrings(GESTURE_REACTION, StringComparisonType.EQUALS).filter(::isGestureReactionComponent)
    val component = components.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one class whose constructor holds \"$GESTURE_REACTION\", found ${components.size}",
    )
    val viewType = mountedView(component)
        ?: throw PatchException("$PATCH: $GESTURE_REACTION's onCreateMountContent doesn't make one view")
    val gestureView = classDefByOrNull(viewType) ?: throw PatchException("$PATCH: this build has no $viewType")
    val listeners = touchListenerCandidates(gestureView).mapNotNull { classDefByOrNull(it) }
        .filter { ON_TOUCH_LISTENER in it.interfaces }
    val listener = listeners.singleOrNull() ?: throw PatchException(
        "$PATCH: expected the gesture view $viewType to set one touch listener it makes, found ${listeners.map { it.type }}",
    )
    val onTouch = onTouches(listener).singleOrNull()
        ?: throw PatchException("$PATCH: ${listener.type} has no onTouch(View, MotionEvent) of its own")

    return FrameStepAnchors(owner, trigger, start, stop, release, safeSeek, seekTo, smallSeek, position, isPlaying, view,
        onTouch)
}

/**
 * Each hook goes first in its method and hands the extension the method's own arguments through
 * the range form, which names any register and borrows none: the player and the trigger for the
 * start, the stop and the release, and the trigger, the player and the position for the static
 * seek. The small-seek check asks the extension first and answers no while a step seeks.
 */
private fun BytecodePatchContext.hookPlayer(anchors: FrameStepAnchors) {
    val owner = mutableClassDefBy(anchors.owner.type)
    owner.findMutableMethodOf(anchors.start).addInstruction(0, "invoke-static/range { p0 .. p1 }, $STARTED")
    owner.findMutableMethodOf(anchors.stop).addInstruction(0, "invoke-static/range { p0 .. p1 }, $STOPPED")
    owner.findMutableMethodOf(anchors.release).addInstruction(0, "invoke-static/range { p0 .. p1 }, $RELEASED")
    owner.findMutableMethodOf(anchors.safeSeek).addInstruction(0, "invoke-static/range { p0 .. p2 }, $SEEKING")
    owner.findMutableMethodOf(anchors.smallSeek).answerNoWhileStepping()
}

/** First in the small-seek check: no while the extension's step is seeking, then the check as Facebook wrote it. */
internal fun MutableMethod.answerNoWhileStepping() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $STEPPING
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", getInstruction(0)),
    )
}

/**
 * Fills the extension's stubs. Each reads only its parameter registers, cast to the player's own
 * types, and the view stub's one parameter is a low register, which its iget needs.
 */
private fun BytecodePatchContext.fillStubs(anchors: FrameStepAnchors) {
    val extension = mutableClassDefBy(FRAME_STEP)
    fun stub(name: String, parameters: List<String>, answer: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: throw PatchException("$FRAME_STEP has no static $answer $name(${parameters.joinToString("")})")

    val owner = anchors.owner.type
    val objectType = "Ljava/lang/Object;"
    stub(POSITION_STUB, listOf(objectType), "I").addInstructions(
        0,
        """
            check-cast p0, $owner
            invoke-virtual/range { p0 .. p0 }, $owner->${anchors.position.name}()I
            move-result p0
            return p0
        """,
    )
    stub(PLAYING_STUB, listOf(objectType), "Z").addInstructions(
        0,
        """
            check-cast p0, $owner
            invoke-virtual/range { p0 .. p0 }, $owner->${anchors.isPlaying.name}()Z
            move-result p0
            return p0
        """,
    )
    stub(VIEW_STUB, listOf(objectType), VIEW_GROUP).addInstructions(
        0,
        """
            check-cast p0, $owner
            iget-object p0, p0, $owner->${anchors.view.name}:$VIEW_GROUP
            return-object p0
        """,
    )
    stub(SEEK_STUB, listOf(objectType, objectType, "I"), "Z").addInstructions(
        0,
        """
            check-cast p0, $owner
            check-cast p1, ${anchors.trigger}
            invoke-virtual/range { p0 .. p2 }, $owner->${anchors.seekTo.name}(${anchors.trigger}I)V
            const/4 p0, 0x1
            return p0
        """,
    )
}
