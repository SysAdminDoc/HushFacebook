/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.resume

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.media.taptoplay.calls
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where Resume long videos hooks, found by kept names only (read from 577 and 580, 2026-09-27). The
 * obfuscated names in these comments are for reviewers; the code never writes one down.
 *
 * FbGrootPlayer is the class holding "FbGrootPlayer.play" (580 LX/5BR;, 577 LX/4qS;), which Tap to
 * play finds the same way. In it:
 *
 * - maybeTrackVideoStart and maybeTrackVideoStop keep their names. Each takes the EventTriggerType
 *   only. The start runs at the end of the static start both the play and the player listener's
 *   deferred start go through (580 A0Q, 577 A0P), after the video is told to play; the stop runs in
 *   the pause's static body (580 A0R, 577 A0Q), in the listener's end-of-video callback, and in the
 *   seek while it pauses a playing video on its way to the new position.
 * - The release holds "FbGrootPlayer.releaseInternal" and takes the trigger and a string (580 A1i,
 *   577 A1h). The bind is Tap to play's, the one holding "FbGrootPlayer.bindVideoSources".
 * - The seek every seek goes through holds "FbGrootPlayer.seekToWithSafeSeek" and is static, taking
 *   the trigger, the player, the position and a flag (580 A0P, 577 A0O). The player's public seek
 *   takes the trigger and the position (580 EeY, 577 Ebk) and hands on to it through a seek with a
 *   third, boolean argument (580 A1f, 577 A1e).
 * - The position reader is the one int method of the interface the player implements that also
 *   declares isPlaying() (580 LX/UvA; BE9, 577 LX/V6h; BG8). Facebook's own remaining-time method
 *   subtracts it from the video's length (580 A1E, 577 A1D): that's the one no-argument long method
 *   of the player that calls the position reader and reads VideoPlayerParams' duration field, and
 *   the int method it asks for the length first is the length reader (580 CLi, 577 CMX). Facebook
 *   takes the params' duration instead when that answers 0 or less, and so does the extension.
 * - The player's VideoPlayerParams getter is its one no-argument method answering that kept class
 *   (580 CM9, 577 CMy).
 *
 * VideoPlayerParams keeps a debug dump that reports each field under its real name (580 EYb, 577
 * EVr). A number or a flag goes through String.valueOf on its way to the reporter, so the pairs are
 * read by following each field's value into the reporter call, whose last two arguments are the
 * name and the value.
 */

internal const val RESUME_PLAYBACK = "$EXTENSION_PACKAGE/media/ResumePlayback;"
internal const val STARTED = "$RESUME_PLAYBACK->started(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val STOPPED = "$RESUME_PLAYBACK->stopped(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val RELEASED = "$RESUME_PLAYBACK->released(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val REBOUND = "$RESUME_PLAYBACK->rebound(Ljava/lang/Object;)V"
internal const val SEEKING = "$RESUME_PLAYBACK->seeking(Ljava/lang/Object;Ljava/lang/Object;I)V"

/** The extension's stubs the patch fills in: name to (parameters, answer). */
internal const val POSITION_STUB = "position"
internal const val DURATION_STUB = "duration"
internal const val PARAMS_STUB = "playerParams"
internal const val SEEK_STUB = "seekPlayer"
internal const val FIELDS_STUB = "paramFields"

internal const val TRACK_START = "maybeTrackVideoStart"
internal const val TRACK_STOP = "maybeTrackVideoStop"
internal const val GROOT_RELEASE = "FbGrootPlayer.releaseInternal"
internal const val GROOT_SAFE_SEEK = "FbGrootPlayer.seekToWithSafeSeek"
internal const val VIDEO_PLAYER_PARAMS = "Lcom/facebook/video/engine/api/VideoPlayerParams;"
internal const val IS_PLAYING = "isPlaying"
private const val STRING = "Ljava/lang/String;"

/**
 * The VideoPlayerParams fields the extension reads, by the name the params' debug dump reports each
 * under, with the type each has to be. The extension's constants of the same names read them.
 */
internal val PARAM_FIELDS = linkedMapOf(
    "videoId" to STRING,
    "videoDurationMs" to "I",
    "startPositionMs" to "I",
    "isLiveNow" to "Z",
    "isFbShorts" to "Z",
    "isSponsored" to "Z",
    "shouldLoopVideo" to "Z",
    "isAnimatedGifVideo" to "Z",
    "isAudioOnly" to "Z",
)

/**
 * The trigger names the extension reads: the one its seek carries, and the ones only something you
 * did sends. A build that renamed one would change the rule without a word, so the patch stops.
 */
internal val RESUME_TRIGGER_NAMES = listOf(
    "BY_PLAYER", "BY_USER", "BY_USER_GESTURE", "BY_SEEKBAR_CONTROLLER", "BY_SEEK", "BY_MEDIA_SESSION_CONTROLS",
)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

private val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

/** [owner]'s kept-name tracker [name]: an instance method taking only [trigger], answering nothing, with a body. */
internal fun trackers(owner: ClassDef, name: String, trigger: String): List<Method> = owner.methods.filter {
    it.name == name && !it.isStatic() && it.returnType == "V" && it.parameters() == listOf(trigger) &&
        it.implementation != null
}

/** [owner]'s release: holds the release trace, takes the trigger and a string, answers nothing. */
internal fun releases(owner: ClassDef, trigger: String): List<Method> = owner.methods.filter {
    !it.isStatic() && it.returnType == "V" && it.parameters() == listOf(trigger, STRING) && holdsString(it, GROOT_RELEASE)
}

/**
 * The seek every seek of [owner] goes through: static, holds its trace, takes the trigger, the player,
 * an int and its flags. Up to 586 that's one flag; 587 added a second (05MK.A0L), which only tells a
 * position listener about the seek.
 */
internal fun safeSeeks(owner: ClassDef, trigger: String): List<Method> = owner.methods.filter {
    val parameters = it.parameters()
    it.isStatic() && it.returnType == "V" && parameters.size >= 4 &&
        parameters.take(3) == listOf(trigger, owner.type, "I") && parameters.drop(3).all { flag -> flag == "Z" } &&
        holdsString(it, GROOT_SAFE_SEEK)
}

/**
 * [owner]'s public seek: an instance method taking the trigger and an int, answering nothing, that
 * hands on to [safeSeek] through a seek of the same class taking one more argument, a flag.
 */
internal fun seekTos(owner: ClassDef, trigger: String, safeSeek: Method): List<Method> {
    val inner = owner.methods.filter {
        !it.isStatic() && it.returnType == "V" && it.parameters() == listOf(trigger, "I", "Z") && calls(it, safeSeek)
    }
    return owner.methods.filter { seek ->
        !seek.isStatic() && seek.returnType == "V" && seek.parameters() == listOf(trigger, "I") &&
            inner.any { calls(seek, it) }
    }
}

/** [owner]'s getters of its VideoPlayerParams: instance methods with no arguments answering that class. */
internal fun paramsGetters(owner: ClassDef): List<Method> = owner.methods.filter {
    !it.isStatic() && it.parameterTypes.isEmpty() && it.returnType == VIDEO_PLAYER_PARAMS && it.implementation != null
}

/** Of [interfaces], the ones declaring isPlaying(). */
internal fun playingInterfaces(interfaces: List<ClassDef>): List<ClassDef> = interfaces.filter { face ->
    face.methods.any { it.name == IS_PLAYING && it.returnType == "Z" && it.parameterTypes.isEmpty() }
}

/**
 * [owner]'s implementation of [face]'s one int method with no arguments. Empty when [face] declares
 * none or several, since then the position reader isn't the one int it answers.
 */
internal fun positionReaders(owner: ClassDef, face: ClassDef): List<Method> {
    val name = face.methods.filter { it.returnType == "I" && it.parameterTypes.isEmpty() }.singleOrNull()?.name
        ?: return emptyList()
    return owner.methods.filter {
        it.name == name && !it.isStatic() && it.returnType == "I" && it.parameterTypes.isEmpty() && it.implementation != null
    }
}

/**
 * [owner]'s remaining-time methods: no arguments, answering a long, calling [position] and reading
 * [durationField] of VideoPlayerParams.
 */
internal fun remainingReaders(owner: ClassDef, position: Method, durationField: String): List<Method> =
    owner.methods.filter { method ->
        val code = method.implementation?.instructions?.toList() ?: return@filter false
        !method.isStatic() && method.parameterTypes.isEmpty() && method.returnType == "J" &&
            calls(method, position) &&
            code.any { instruction ->
                val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                instruction.opcode == Opcode.IGET && field?.definingClass == VIDEO_PLAYER_PARAMS && field.name == durationField
            }
    }

/** The other no-argument int methods of [owner] that [remaining] calls: the length reader, once there's one. */
internal fun lengthReaders(owner: ClassDef, remaining: Method, position: Method): List<Method> {
    val called = remaining.implementation?.instructions?.mapNotNull { it.call }?.filter {
        it.definingClass == owner.type && it.parameterTypes.isEmpty() && it.returnType == "I" && it.name != position.name
    }?.map { it.name }?.toSet().orEmpty()
    return owner.methods.filter {
        it.name in called && !it.isStatic() && it.returnType == "I" && it.parameterTypes.isEmpty() && it.implementation != null
    }
}

/** VideoPlayerParams' debug dumps: the methods reporting every one of [PARAM_FIELDS]. */
internal fun paramDumps(params: ClassDef): List<Method> = params.methods.filter { method ->
    PARAM_FIELDS.keys.all { holdsString(method, it) }
}

private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/**
 * The fields [dump] reports, by the name it reports each under. Each field of its own class it
 * reads is followed through String.valueOf into the call that reports it, whose last argument is
 * the value and whose one before is the name, a string it loaded. The dump runs straight down, so
 * a register holds what the last instruction to write it put there.
 */
internal fun reportedValues(dump: Method): Map<String, FieldReference> {
    val owner = dump.definingClass
    val strings = HashMap<Int, String>()
    val fields = HashMap<Int, FieldReference>()
    val reported = LinkedHashMap<String, FieldReference>()
    var converted: FieldReference? = null
    for (instruction in dump.implementation?.instructions ?: return emptyMap()) {
        val string = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        val call = instruction.call
        val written = (instruction as? OneRegisterInstruction)?.registerA
        when {
            string != null && written != null -> {
                strings[written] = string
                fields.remove(written)
            }
            field != null && instruction.opcode.name.startsWith("iget") && written != null -> {
                strings.remove(written)
                if (field.definingClass == owner) fields[written] = field else fields.remove(written)
            }
            call != null -> {
                val arguments = instruction.registers()
                converted = null
                if (call.definingClass == STRING && call.name == "valueOf" && arguments.isNotEmpty()) {
                    converted = fields[arguments.first()]
                } else if (arguments.size >= 2) {
                    val value = fields[arguments.last()]
                    val name = strings[arguments[arguments.size - 2]]
                    if (value != null && name != null) reported.putIfAbsent(name, value)
                }
            }
            instruction.opcode == Opcode.MOVE_RESULT_OBJECT && written != null -> {
                strings.remove(written)
                val value = converted
                if (value != null) fields[written] = value else fields.remove(written)
                converted = null
            }
            written != null && instruction.opcode.setsRegister() -> {
                strings.remove(written)
                fields.remove(written)
                if (instruction.opcode.setsWideRegister()) {
                    strings.remove(written + 1)
                    fields.remove(written + 1)
                }
            }
        }
    }
    return reported
}

/** The names [reported] is missing or holds with another type than [PARAM_FIELDS] wants, as "name (type)". */
internal fun wrongParamFields(reported: Map<String, FieldReference>): List<String> = PARAM_FIELDS.mapNotNull { (name, type) ->
    val field = reported[name]
    when {
        field == null -> "$name (missing)"
        field.type != type -> "$name (${field.type}, wanted $type)"
        else -> null
    }
}

/** What the extension's paramFields stub answers: "reported=field;..." for every one of [PARAM_FIELDS]. */
internal fun paramFieldNames(reported: Map<String, FieldReference>): String =
    PARAM_FIELDS.keys.joinToString(";") { "$it=${reported.getValue(it).name}" }
