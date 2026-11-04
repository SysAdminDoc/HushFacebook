/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.resume

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.grootBinds
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

internal const val PATCH = "Resume long videos"

/**
 * FbGrootPlayer's start, stop, release, bind and seek tell the extension about themselves, and the
 * extension's stubs are filled with the player's position and length readers, its params getter,
 * its seek and the params' field names. The extension decides the rest; see its ResumePlayback.
 */
@Suppress("unused")
val resumeLongVideosPatch = bytecodePatch(
    name = "Resume long videos",
    description = "A video longer than two minutes picks up where you left off the next time it plays, in the " +
        "feed or full screen, so you don't lose your place. Reels, live videos and ads start as usual. Remember " +
        "what you watch, a second switch, keeps a list on your phone of the reels, videos and stories you " +
        "watched, under Recently watched. Both start off. Turn them on in Hushfacebook settings > Video and audio. " +
        "It also lets Take breaks count reels for its Also after 20 reels switch.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val player = resumePlayer()
        hookPlayer(player)
        fillStubs(player)
        enableStatus("resumeLongVideos")
    }
}

/** What the patch found of FbGrootPlayer. */
internal class ResumePlayer(
    val owner: ClassDef,
    val trigger: String,
    val start: Method,
    val stop: Method,
    val release: Method,
    val bind: Method,
    val safeSeek: Method,
    val seekTo: Method,
    val params: Method,
    val position: Method,
    val length: Method,
    val fieldNames: String,
)

/** Finds everything the patch needs of FbGrootPlayer and VideoPlayerParams, or stops naming what's missing. */
internal fun BytecodePatchContext.resumePlayer(): ResumePlayer {
    val plays = classDefByStrings(GROOT_PLAY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap(::grootPlays)
    val play = plays.singleOrNull()
        ?: throw PatchException("$PATCH: expected one player play holding \"$GROOT_PLAY\", found ${plays.size}")
    val owner = classDefBy(play.definingClass)
    val trigger = play.parameterTypes.single().toString()
    val triggerEnum = classDefByOrNull(trigger)
        ?: throw PatchException("$PATCH: the play's trigger type $trigger isn't in this build")
    if (!isEnumNaming(triggerEnum, RESUME_TRIGGER_NAMES)) {
        throw PatchException("$PATCH: the play's trigger type $trigger isn't an enum naming ${RESUME_TRIGGER_NAMES.joinToString()}")
    }

    fun single(what: String, found: List<Method>): Method = found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $what in ${owner.type}, found ${found.size}")

    val start = single("$TRACK_START($trigger)", trackers(owner, TRACK_START, trigger))
    val stop = single("$TRACK_STOP($trigger)", trackers(owner, TRACK_STOP, trigger))
    val release = single("release holding \"$GROOT_RELEASE\"", releases(owner, trigger))
    val bind = single("bind of Tap to play's", grootBinds(owner))
    val safeSeek = single("static seek holding \"$GROOT_SAFE_SEEK\"", safeSeeks(owner, trigger))
    val seekTo = single("seek taking the trigger and an int", seekTos(owner, trigger, safeSeek))
    val params = single("getter of its $VIDEO_PLAYER_PARAMS", paramsGetters(owner))

    val interfaces = owner.interfaces.mapNotNull { classDefByOrNull(it) }
    val playing = playingInterfaces(interfaces).singleOrNull() ?: throw PatchException(
        "$PATCH: expected one interface of ${owner.type} declaring $IS_PLAYING(), found " +
            "${playingInterfaces(interfaces).size} of ${owner.interfaces.size}",
    )
    val position = single("int method of ${playing.type}", positionReaders(owner, playing))

    val paramsClass = classDefByOrNull(VIDEO_PLAYER_PARAMS)
        ?: throw PatchException("$PATCH: this build has no $VIDEO_PLAYER_PARAMS")
    val dumps = paramDumps(paramsClass)
    val dump = dumps.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method of $VIDEO_PLAYER_PARAMS reporting ${PARAM_FIELDS.keys.joinToString()}, found ${dumps.size}",
    )
    val reported = reportedValues(dump)
    val wrong = wrongParamFields(reported)
    if (wrong.isNotEmpty()) {
        throw PatchException("$PATCH: $VIDEO_PLAYER_PARAMS->${dump.name} doesn't report ${wrong.joinToString()}")
    }

    val remaining = single("remaining-time method reading the position and the params' duration",
        remainingReaders(owner, position, reported.getValue("videoDurationMs").name))
    val length = single("length reader the remaining-time method asks", lengthReaders(owner, remaining, position))

    // The extension's stubs call these from outside Facebook's package.
    listOf(owner, triggerEnum, paramsClass).forEach { reachable ->
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) {
            throw PatchException("$PATCH: ${reachable.type} isn't public, so the extension can't reach it")
        }
    }
    listOf(position, length, params, seekTo).forEach { method ->
        if (!AccessFlags.PUBLIC.isSet(method.accessFlags)) {
            throw PatchException("$PATCH: ${owner.type}->${method.name} isn't public, so the extension can't call it")
        }
    }

    return ResumePlayer(owner, trigger, start, stop, release, bind, safeSeek, seekTo, params, position, length,
        paramFieldNames(reported))
}

/**
 * Each hook goes first in its method and hands the extension the method's own arguments through
 * the range form, which names any register and borrows none: the player and the trigger for the
 * start, the stop and the release, the player for the bind, and the trigger, the player and the
 * position for the static seek.
 */
private fun BytecodePatchContext.hookPlayer(player: ResumePlayer) {
    val owner = mutableClassDefBy(player.owner.type)
    owner.findMutableMethodOf(player.start).addInstruction(0, "invoke-static/range { p0 .. p1 }, $STARTED")
    owner.findMutableMethodOf(player.stop).addInstruction(0, "invoke-static/range { p0 .. p1 }, $STOPPED")
    owner.findMutableMethodOf(player.release).addInstruction(0, "invoke-static/range { p0 .. p1 }, $RELEASED")
    owner.findMutableMethodOf(player.bind).addInstruction(0, "invoke-static/range { p0 .. p0 }, $REBOUND")
    owner.findMutableMethodOf(player.safeSeek).addInstruction(0, "invoke-static/range { p0 .. p2 }, $SEEKING")
}

/**
 * Fills the extension's stubs. Each reads only its parameter registers, cast to the player's own
 * types, so the stub's register count doesn't matter beyond the one the field names' string needs.
 */
private fun BytecodePatchContext.fillStubs(player: ResumePlayer) {
    val extension = mutableClassDefBy(RESUME_PLAYBACK)
    fun stub(name: String, parameters: List<String>, answer: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: throw PatchException("$RESUME_PLAYBACK has no static $answer $name(${parameters.joinToString("")})")

    val owner = player.owner.type
    val objectType = "Ljava/lang/Object;"
    listOf(POSITION_STUB to player.position, DURATION_STUB to player.length).forEach { (name, reader) ->
        stub(name, listOf(objectType), "I").addInstructions(
            0,
            """
                check-cast p0, $owner
                invoke-virtual/range { p0 .. p0 }, $owner->${reader.name}()I
                move-result p0
                return p0
            """,
        )
    }
    stub(PARAMS_STUB, listOf(objectType), objectType).addInstructions(
        0,
        """
            check-cast p0, $owner
            invoke-virtual/range { p0 .. p0 }, $owner->${player.params.name}()$VIDEO_PLAYER_PARAMS
            move-result-object p0
            return-object p0
        """,
    )
    stub(SEEK_STUB, listOf(objectType, objectType, "I"), "Z").addInstructions(
        0,
        """
            check-cast p0, $owner
            check-cast p1, ${player.trigger}
            invoke-virtual/range { p0 .. p2 }, $owner->${player.seekTo.name}(${player.trigger}I)V
            const/4 p0, 0x1
            return p0
        """,
    )
    stub(FIELDS_STUB, emptyList(), "Ljava/lang/String;").returnEarly(player.fieldNames)
}
