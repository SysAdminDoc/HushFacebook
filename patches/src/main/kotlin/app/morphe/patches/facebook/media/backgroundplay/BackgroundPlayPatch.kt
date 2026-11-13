/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.backgroundplay

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.TRIGGER_NAMES
import app.morphe.patches.facebook.media.taptoplay.grootPauses
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.media.taptoplay.hookTouches
import app.morphe.patches.facebook.media.taptoplay.innerPause
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val PATCH = "Keep playing in the background"

/**
 * A video you started keeps its sound after you leave Facebook, through Facebook's own background
 * playback: its manager, its fullscreen handler's new player and its notification service, which
 * the manifest already declares. The patch lets the extension make that manager and handler, has
 * the handler take a video only when the extension says you started it and it was playing when you
 * left, and has the notification's server flag read on for that video alone.
 */
@Suppress("unused")
val backgroundPlayPatch = bytecodePatch(
    name = "Keep playing in the background",
    description = "A video you started with a tap keeps its sound when you leave Facebook, with Facebook's own " +
        "notification to pause it. A headphones button in the picture-in-picture window does the same for the video " +
        "in it. Both start off. Turn them on in Hushfacebook settings > Video and audio.",
) {
    category("Playback")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val player = findPlayer()
        val handler = findHandler(player.trigger)
        fillStubs(player.videoIdAccessor, player.triggerPause.name, handler.classDef.type)
        hookPlayer(player)
        hookHandler(handler)
        hookWindowButtons()
        hookTouches(PATCH, doubleTap = false)
        enableStatus("backgroundPlay")
    }
}

/**
 * FbGrootPlayer's play, its inner pause, the pause taking only a trigger, its trigger type and its
 * video id accessor.
 */
internal class Player(
    val owner: ClassDef,
    val play: Method,
    val pause: Method,
    val triggerPause: Method,
    val trigger: String,
    val videoIdAccessor: String,
)

/** The fullscreen handler, its check and return, the record's fields and the notification's gate. */
internal class Handler(
    val classDef: ClassDef,
    val check: Method,
    val back: Method,
    val read: HandlerCheck,
    val videoField: String,
    val triggerField: String,
    val record: String,
    val trigger: String,
    val gate: Method,
)

private fun BytecodePatchContext.findPlayer(): Player {
    val plays = classDefByStrings(GROOT_PLAY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap(::grootPlays)
    val play = plays.singleOrNull()
        ?: throw PatchException("$PATCH: expected one player play holding \"$GROOT_PLAY\", found ${plays.size}")
    val trigger = play.parameterTypes.single().toString()
    val triggers = classDefByOrNull(trigger)
    if (triggers == null || !isEnumNaming(triggers, TRIGGER_NAMES + BACKGROUND_TRIGGERS)) {
        throw PatchException("$PATCH: the play's trigger type $trigger isn't an enum naming ${BACKGROUND_TRIGGERS.joinToString()}")
    }
    val owner = classDefBy(play.definingClass)
    val pauses = grootPauses(owner, trigger)
    val pause = innerPause(pauses)
        ?: throw PatchException("$PATCH: expected one inner pause in ${owner.type} that the other hands on to")
    val triggerPause = triggerPause(pauses, pause, trigger)
        ?: throw PatchException("$PATCH: expected one public pause in ${owner.type} taking only $trigger")
    val helper = pauseHelper(owner, pause, trigger) { classDefByOrNull(it) }
        ?: throw PatchException("$PATCH: the inner pause of ${owner.type} hands on to no one static helper")
    val calls = recorderCalls(helper, trigger)
    val call = calls.singleOrNull() ?: throw PatchException(
        "$PATCH: expected ${helper.definingClass}->${helper.name} to record the pause once, found ${calls.size}",
    )
    val accessor = videoIdAccessor(helper, call, owner.type) ?: throw PatchException(
        "$PATCH: the video id ${helper.definingClass}->${helper.name} records doesn't come from one accessor of the player",
    )
    return Player(owner, play, pause, triggerPause, trigger, accessor)
}

private fun BytecodePatchContext.findHandler(trigger: String): Handler {
    val manager = classDefByOrNull(MANAGER)
    if (manager == null || !isBackgroundManager(manager)) {
        throw PatchException(
            "$PATCH: this build has no $MANAGER with a public constructor, one list of handlers and an " +
                "onActivityStopped holding \"$MANAGER_ON_BACKGROUND\"",
        )
    }
    val questions = handlerQuestions(managerStops(manager)!!)
    val question = questions.singleOrNull() ?: throw PatchException(
        "$PATCH: expected the manager's stop to ask its handlers one (session, record) question, found ${questions.size}",
    )
    val record = question.parameterTypes[1].toString()
    val handlers = classDefByStrings(FULLSCREEN_HANDLER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter { isFullscreenHandler(it, question.definingClass) }
    val handler = handlers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one handler of ${question.definingClass} tagged \"$FULLSCREEN_HANDLER\", found ${handlers.size}",
    )
    val check = handlerChecks(handler, record).singleOrNull()
        ?: throw PatchException("$PATCH: ${handler.type} has no one check of a $record")
    val back = handlerReturns(handler, record).singleOrNull()
        ?: throw PatchException("$PATCH: ${handler.type} has no one return taking a $record")
    val recordClass = classDefByOrNull(record) ?: throw PatchException("$PATCH: the record $record isn't in this build")
    val videoField = recordFields(recordClass, STRING).singleOrNull()
        ?: throw PatchException("$PATCH: expected $record to keep one String, its video id")
    val triggerField = recordFields(recordClass, trigger).singleOrNull()
        ?: throw PatchException("$PATCH: expected $record to keep one $trigger")
    val read = try {
        readHandlerCheck(check, record)
    } catch (unexpected: IllegalStateException) {
        throw PatchException("$PATCH: ${handler.type}->${check.name}: ${unexpected.message}")
    }
    val starterClass = classDefByOrNull(read.starter.definingClass)
        ?: throw PatchException("$PATCH: the notification's starter ${read.starter.definingClass} isn't in this build")
    val starter = starterClass.methods.singleOrNull {
        it.name == read.starter.name && it.parameterTypes.map(CharSequence::toString) ==
            read.starter.parameterTypes.map(CharSequence::toString) && it.implementation != null
    } ?: throw PatchException("$PATCH: ${read.starter.definingClass}->${read.starter.name} has no body here")
    val gate = notificationGate(starterClass, starter) ?: throw PatchException(
        "$PATCH: ${starterClass.type} doesn't start $NOTIFICATION_SERVICE with \"$AUDIO_PRODUCT\" behind one (Z)Z check",
    )
    return Handler(handler, check, back, read, videoField.name, triggerField.name, record, trigger, gate)
}

/** Fills in the extension's stubs: the player's video id accessor and trigger pause, and the handler's class. */
private fun BytecodePatchContext.fillStubs(videoIdAccessor: String, triggerPause: String, handlerType: String) {
    val extension = mutableClassDefBy(BACKGROUND_PLAY)
    listOf(
        VIDEO_ID_METHOD to videoIdAccessor,
        PAUSE_METHOD to triggerPause,
        HANDLER_CLASS to binaryName(handlerType),
    ).forEach { (stub, value) ->
        extension.methods.singleOrNull { it.name == stub && it.parameterTypes.isEmpty() && it.returnType == STRING }
            ?.returnEarly(value)
            ?: throw PatchException("$PATCH: the extension has no $stub() to fill in")
    }
}

/** The play and the inner pause tell the extension first, with the player and the trigger. */
private fun BytecodePatchContext.hookPlayer(player: Player) {
    val owner = mutableClassDefBy(player.owner.type)
    // Both take the trigger first, so the player and the trigger are p0 and p1 and the range form
    // passes them without borrowing a register.
    owner.findMutableMethodOf(player.play).addInstruction(0, "invoke-static/range { p0 .. p1 }, $STARTED")
    owner.findMutableMethodOf(player.pause).addInstruction(0, "invoke-static/range { p0 .. p1 }, $PAUSING")
}

private fun BytecodePatchContext.hookHandler(handler: Handler) {
    val mutable = mutableClassDefBy(handler.classDef.type)
    hookCheck(mutable.findMutableMethodOf(handler.check), handler)
    hookReturn(mutable.findMutableMethodOf(handler.back))

    // The notification's server flag reads on while the extension carries a video on.
    val gate = mutableClassDefBy(handler.gate.definingClass).findMutableMethodOf(handler.gate)
    gate.requireLocals(PATCH, 1)
    gate.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $NOTIFICATION_ALLOWED
            move-result v0
            if-eqz v0, :facebook
            return v0
        """.trimIndent(),
        ExternalLabel("facebook", gate.getInstruction(0)),
    )
}

/**
 * The handler's check: first the extension decides on the record's video and trigger, and a yes to
 * stop answers no at once; each of Facebook's checks the extension skips asks it again; the title and
 * owner go through it; and each answer is told to it. The code goes in from the last point up, so
 * the indices read before stay right.
 */
internal fun hookCheck(check: MutableMethod, handler: Handler) {
    val read = handler.read
    val returns = check.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }
    val points = (returns + read.branches.keys).sortedDescending()
    for (index in points) {
        val kind = read.branches[index]
        when {
            kind == null -> {
                val register = check.getInstruction<OneRegisterInstruction>(index).registerA
                check.addInstructionsAtControlFlowLabel(index, "invoke-static/range { v$register .. v$register }, $ANSWERED")
            }
            kind == CheckKind.ASKS || kind == CheckKind.RECORD_ENUM -> {
                val free = check.freeLocalsAt(PATCH, index, 1, targets = listOf(index + 1), highest = 255).single()
                check.addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { }, $SKIP_CHECK
                        move-result v$free
                        if-nez v$free, :checked
                    """.trimIndent(),
                    ExternalLabel("checked", check.getInstruction(index + 1)),
                )
            }
            kind == CheckKind.TITLE || kind == CheckKind.SUBTITLE -> {
                val register = if (kind == CheckKind.TITLE) read.titleRegister else read.subtitleRegister
                val call = if (kind == CheckKind.TITLE) TITLE else SUBTITLE
                check.addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static/range { v$register .. v$register }, $call
                        move-result-object v$register
                    """.trimIndent(),
                )
            }
            // The record's pause flag, its age and the story, media and media id Facebook reads stay.
            else -> Unit
        }
    }

    // Nothing has run yet, so v0 to v2 are free.
    check.requireLocals(PATCH, 3)
    check.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${check.parameterRegister(1)}
            iget-object v1, v0, ${handler.record}->${handler.videoField}:$STRING
            iget-object v2, v0, ${handler.record}->${handler.triggerField}:${handler.trigger}
            move-object/from16 v0, p0
            invoke-static { v0, v1, v2 }, $DECIDING
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """.trimIndent(),
        ExternalLabel("facebook", check.getInstruction(0)),
    )
}

/** The picture-in-picture window's buttons, as Facebook builds them. */
internal fun BytecodePatchContext.windowActionBuilders(): List<Method> =
    classDefByStrings(WINDOW_SKIP_BACK, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter(::isWindowActionBuilder) }

/** Each list of window buttons Facebook answers goes through the extension, which can add headphones. */
private fun BytecodePatchContext.hookWindowButtons() {
    val builders = windowActionBuilders()
    val builder = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one builder of the window's buttons holding \"$WINDOW_SKIP_BACK\", found ${builders.size}",
    )
    val returns = objectReturns(builder)
    if (returns.isEmpty()) throw PatchException("$PATCH: ${builder.definingClass}->${builder.name} returns no list")
    val mutable = mutableClassDefBy(builder.definingClass).findMutableMethodOf(builder)
    returns.sortedDescending().forEach { index ->
        val register = mutable.getInstruction<OneRegisterInstruction>(index).registerA
        mutable.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $WINDOW_ACTIONS
                move-result-object v$register
            """.trimIndent(),
        )
    }
}

/** The handler's return to Facebook tells the extension first and again before each of its returns. */
internal fun hookReturn(back: MutableMethod) {
    back.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        .sortedDescending()
        .forEach { back.addInstructionsAtControlFlowLabel(it, "invoke-static { }, $RETURNED") }
    back.addInstruction(0, "invoke-static/range { p0 .. p0 }, $RETURNING")
}
