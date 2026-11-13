/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.taptoplay

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.font.objectReturns
import app.morphe.patches.facebook.media.resume.POSITION_STUB
import app.morphe.patches.facebook.media.resume.VIDEO_PLAYER_PARAMS
import app.morphe.patches.facebook.media.resume.paramsGetters
import app.morphe.patches.facebook.media.resume.playingInterfaces
import app.morphe.patches.facebook.media.resume.positionReaders
import app.morphe.patches.facebook.media.resume.reportedValues
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.reels.feedVideoOpeningPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val PATCH = "Tap to play"

/**
 * Every start of Facebook's media players asks the extension first, and a start that no tap asked
 * for is held. Facebook's own Autoplay setting reads Off while the switch is on, so the surfaces
 * that ask it draw their own play button, and the players' pauses and new videos tell the
 * extension when a start a tap let through has ended. The extension's reel check is filled with the
 * player's params getter and their isFbShorts, so a reel's BY_AUTOPLAY start counts as the reel
 * coming into view.
 */
@Suppress("unused")
val tapToPlayPatch = bytecodePatch(
    name = "Tap to play",
    description = "Videos, reels, stories and music wait for your tap instead of starting by themselves, so " +
        "nothing plays until you want it to. A separate switch adds a play/pause button to feed videos. " +
        "The switches start off in Hushfacebook settings > Video and audio.",
) {
    category("Playback")
    // A tapped feed video's player can start well after the tap; these hooks say one is opening (#393).
    dependsOn(settingsPatch, feedVideoOpeningPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val play = hookGrootPlayer()
        installFeedVideoControls(play)
        hookLegacyPlayer(play.parameterTypes.single().toString())
        // A build where the params moved keeps the rest of the patch.
        try {
            fillReelCheck(classDefBy(play.definingClass))
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on, and a reel Facebook starts with BY_AUTOPLAY " +
                "counts as any other video's start.")
        }
        try {
            fillPositionReader(classDefBy(play.definingClass))
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without saying where a reel's start came.")
        }
        try {
            hookCenteredFullscreen()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on, and full screen videos open as Facebook lays them out.")
        }
        val checker = hookAutoplaySetting()
        hookReelPlayButton(checker)
        hookReelPlayback()
        try {
            hookReelAutoplayDisabled()
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on, and a reel's play button can come back while it plays.")
        }
        hookTouches(PATCH, doubleTap = true)
        enableStatus("tapToPlay")
    }
}

/** FbGrootPlayer's play, its inner pause and its bind. Answers the play, which takes the trigger. */
private fun BytecodePatchContext.hookGrootPlayer(): Method {
    val plays = classDefByStrings(GROOT_PLAY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap(::grootPlays)
    val play = plays.singleOrNull()
        ?: throw PatchException("$PATCH: expected one player play holding \"$GROOT_PLAY\", found ${plays.size}")
    val trigger = play.parameterTypes.single().toString()
    requireTriggerEnum(trigger)
    val owner = classDefBy(play.definingClass)

    val pause = innerPause(grootPauses(owner, trigger)) ?: throw PatchException(
        "$PATCH: expected one \"$GROOT_PAUSE\" pause in ${owner.type} that every other one hands on to, " +
            "found ${grootPauses(owner, trigger).size} pause(s)",
    )
    val binds = grootBinds(owner)
    val bind = binds.singleOrNull()
        ?: throw PatchException("$PATCH: expected one bind holding \"$GROOT_BIND\" in ${owner.type}, found ${binds.size}")

    val mutableOwner = mutableClassDefBy(owner.type)
    gateStart(mutableOwner.findMutableMethodOf(play), 0, ALLOW_START)
    tellFirst(mutableOwner.findMutableMethodOf(pause), PAUSED)
    tellFirst(mutableOwner.findMutableMethodOf(bind), REBOUND)
    return play
}

/**
 * Fills the extension's reel check with [owner]'s one VideoPlayerParams getter and the boolean field
 * the params' debug dumps report as isFbShorts. Each stub reads only its parameter, cast to the
 * player's or the params' own type, and both are found before either changes.
 */
private fun BytecodePatchContext.fillReelCheck(owner: ClassDef) {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: reel check: $detail")
    val objectType = "Ljava/lang/Object;"
    val getters = paramsGetters(owner)
    val getter = getters.singleOrNull()
        ?: refuse("expected one getter of $VIDEO_PLAYER_PARAMS in ${owner.type}, found ${getters.size}")
    val params = classDefByOrNull(VIDEO_PLAYER_PARAMS) ?: refuse("this build has no $VIDEO_PLAYER_PARAMS")
    val fields = params.methods.filter { holdsString(it, FB_SHORTS) }
        .mapNotNull { reportedValues(it)[FB_SHORTS] }.distinctBy(FieldReference::toString)
    val field = fields.singleOrNull()
        ?: refuse("expected $VIDEO_PLAYER_PARAMS to report $FB_SHORTS from one field, found ${fields.size}")
    val declared = params.fields.singleOrNull {
        it.name == field.name && it.type == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$FB_SHORTS is ${field.name}:${field.type}, not a boolean field of $VIDEO_PLAYER_PARAMS")
    // The stubs run in the extension's package.
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuse("$FB_SHORTS, ${field.name}, isn't public")
    listOf(owner, params).forEach { if (!AccessFlags.PUBLIC.isSet(it.accessFlags)) refuse("${it.type} isn't public") }
    if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("${owner.type}->${getter.name} isn't public")

    if (classDefByOrNull(TAP_TO_PLAY) == null) refuse("the extension has no $TAP_TO_PLAY")
    val extension = mutableClassDefBy(TAP_TO_PLAY)
    fun stub(name: String, answer: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == answer && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(objectType)
    } ?: refuse("$TAP_TO_PLAY has no static $answer $name($objectType)")
    val paramsStub = stub(PLAYER_PARAMS_STUB, objectType)
    val flagStub = stub(FB_SHORTS_STUB, "Z")
    paramsStub.addInstructions(
        0,
        """
            check-cast p0, ${owner.type}
            invoke-virtual/range { p0 .. p0 }, ${owner.type}->${getter.name}()$VIDEO_PLAYER_PARAMS
            move-result-object p0
            return-object p0
        """,
    )
    flagStub.addInstructions(
        0,
        """
            check-cast p0, $VIDEO_PLAYER_PARAMS
            iget-boolean p0, p0, $VIDEO_PLAYER_PARAMS->${field.name}:Z
            return p0
        """,
    )
}

/**
 * Fills the extension's position stub with [owner]'s position reader: its one int method from the
 * interface that declares isPlaying(), the reader Resume long videos uses too. Tap to play counts the
 * reel starts that come with the player already partway in (#90), whichever other patches are in.
 */
internal fun BytecodePatchContext.fillPositionReader(owner: ClassDef) {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: position reader: $detail")
    val playing = playingInterfaces(owner.interfaces.mapNotNull { classDefByOrNull(it) })
    val face = playing.singleOrNull() ?: refuse("expected one interface of ${owner.type} declaring isPlaying(), found ${playing.size}")
    val readers = positionReaders(owner, face)
    val reader = readers.singleOrNull() ?: refuse("expected one int method of ${face.type} in ${owner.type}, found ${readers.size}")
    // The stub runs in the extension's package.
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags)) refuse("${owner.type} isn't public")
    if (!AccessFlags.PUBLIC.isSet(reader.accessFlags)) refuse("${owner.type}->${reader.name} isn't public")

    if (classDefByOrNull(TAP_TO_PLAY) == null) refuse("the extension has no $TAP_TO_PLAY")
    val stub = mutableClassDefBy(TAP_TO_PLAY).methods.singleOrNull {
        it.name == POSITION_STUB && it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
    } ?: refuse("$TAP_TO_PLAY has no static int $POSITION_STUB(Ljava/lang/Object;)")
    stub.addInstructions(
        0,
        """
            check-cast p0, ${owner.type}
            invoke-virtual/range { p0 .. p0 }, ${owner.type}->${reader.name}()I
            move-result p0
            return p0
        """,
    )
}

/** The older Rich Video Player's playback controller: its play and its pause. */
private fun BytecodePatchContext.hookLegacyPlayer(trigger: String) {
    val plays = classDefByStrings(LEGACY_PLAY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { legacyPlays(it, trigger) }
    val play = plays.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one older player play holding \"$LEGACY_PLAY\" and taking $trigger, found ${plays.size}",
    )
    val owner = classDefBy(play.definingClass)
    val pauses = legacyPauses(owner, trigger)
    val pause = pauses.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one older player pause holding \"$LEGACY_PAUSE\" in ${owner.type}, found ${pauses.size}",
    )
    val mutableOwner = mutableClassDefBy(owner.type)
    gateStart(mutableOwner.findMutableMethodOf(play), play.parameterTypes.indexOfFirst { it.toString() == trigger },
        ALLOW_LEGACY_START)
    tellFirst(mutableOwner.findMutableMethodOf(pause), PAUSED)
}

/**
 * Each answer of Facebook's Autoplay setting reader goes through the extension on its way out.
 * Answers the checker's type.
 */
private fun BytecodePatchContext.hookAutoplaySetting(): String {
    val checkers = classDefByStrings(AUTOPLAY_SETTINGS_CHECKER, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .filter(::isAutoplaySettingsChecker)
    val checker = checkers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one Autoplay settings checker refusing with \"$AUTOPLAY_SETTINGS_CHECKER\", found ${checkers.size}",
    )
    val readers = settingReaders(checker) { type -> classDefByOrNull(type)?.let { isEnumNaming(it, SETTING_NAMES) } == true }
    val reader = readers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one method of ${checker.type} answering the enum of ${SETTING_NAMES.joinToString()}, " +
            "found ${readers.size}",
    )
    val mutable = mutableClassDefBy(checker.type).findMutableMethodOf(reader)
    val returns = objectReturns(mutable)
    if (returns.isEmpty()) throw PatchException("$PATCH: ${checker.type}->${reader.name} returns no object")
    // The answer goes out and back in its own register, which the return reads next, so nothing
    // else is borrowed. The range form names any register.
    returns.asReversed().forEach { index ->
        val register = mutable.getInstruction<OneRegisterInstruction>(index).registerA
        mutable.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $AUTOPLAY_SETTING
                move-result-object v$register
                check-cast v$register, ${reader.returnType}
            """.trimIndent(),
        )
    }
    return checker.type
}

/**
 * First thing in the Reels controls' autoplay-off check, the extension answers yes while the switch
 * is on, so a reel the gate holds shows its play button and one tap plays it. The check's first
 * boolean, which makes Facebook's own answer no, goes to the extension with it; a no from the
 * extension runs Facebook's own check.
 */
private fun BytecodePatchContext.hookReelPlayButton(checker: String) {
    val checks = classDefByStrings(REELS_CONTROLS, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap(::autoplayOffChecksCalled).toSet()
    val signature = checks.singleOrNull() ?: throw PatchException(
        "$PATCH: expected the classes holding \"$REELS_CONTROLS\" to call one (session, config, Z, Z)Z check, " +
            "found ${checks.size}",
    )
    val owner = classDefByOrNull(signature.substringBefore("->"))
        ?: throw PatchException("$PATCH: the Reels autoplay-off check's class isn't in this build")
    val check = methodNamed(owner, signature)
        ?: throw PatchException("$PATCH: $signature has no body in this build")
    if (!asksWithSession(check, checker)) {
        throw PatchException("$PATCH: $signature doesn't ask the Autoplay settings checker $checker")
    }
    val mutable = mutableClassDefBy(owner.type).findMutableMethodOf(check)
    mutable.requireLocals(PATCH, 1)
    mutable.addInstructionsWithLabels(
        0,
        """
            move/from16 v0, ${mutable.parameterRegister(2)}
            invoke-static { v0 }, $SHOW_REEL_PLAY_BUTTON
            move-result v0
            if-eqz v0, :check
            return v0
        """.trimIndent(),
        ExternalLabel("check", mutable.getInstruction(0)),
    )
}

/**
 * Every touch on a Facebook screen goes to the feed video double tap, then to the tap clock, before
 * Facebook sees it. The double tap turns a held side tap into a cancel first, so the clock sees what
 * Facebook sees (#432). Tap to play and Keep playing in the background both read the clock, so each
 * asks for this hook and the clock's call goes in once; [doubleTap] is Tap to play's, which adds the
 * double tap in front of it. [patch] names the patch asking.
 */
internal fun BytecodePatchContext.hookTouches(patch: String, doubleTap: Boolean) {
    val activity = classDefByOrNull(FRAGMENT_ACTIVITY)
        ?: throw PatchException("$patch: this build has no $FRAGMENT_ACTIVITY")
    val dispatches = touchDispatches(activity)
    val dispatch = dispatches.singleOrNull() ?: throw PatchException(
        "$patch: expected $FRAGMENT_ACTIVITY to declare one dispatchTouchEvent($MOTION_EVENT)Z, found ${dispatches.size}",
    )
    val mutable = mutableClassDefBy(FRAGMENT_ACTIVITY).findMutableMethodOf(dispatch)
    val hooked = touchHooksAtStart(mutable)
    // The screen and the event are p0 and p1, next to each other, so the range form passes both
    // without borrowing a register.
    val calls = listOfNotNull(
        DOUBLE_TAP_TOUCH.takeIf { doubleTap && it !in hooked },
        TOUCH.takeIf { it !in hooked },
    )
    if (calls.isEmpty()) return
    mutable.addInstructions(0, calls.joinToString("\n") { "invoke-static/range { p0 .. p1 }, $it" })
}

private fun BytecodePatchContext.requireTriggerEnum(type: String) {
    val enum = classDefByOrNull(type) ?: throw PatchException("$PATCH: the play's trigger type $type isn't in this build")
    if (!isEnumNaming(enum, TRIGGER_NAMES)) {
        throw PatchException(
            "$PATCH: the play's trigger type $type isn't an enum naming ${TRIGGER_NAMES.joinToString()}",
        )
    }
}

/**
 * First thing in [method], the player and the trigger in declared parameter [triggerIndex] go to
 * [allow], and a no returns before the player does anything. The two are copied into v0 and v1,
 * which hold nothing at the method's first instruction, through the 16-bit form, so the call reads
 * the same whatever registers the frame keeps the parameters in.
 */
internal fun gateStart(method: MutableMethod, triggerIndex: Int, allow: String) {
    if (method.returnType != "V") throw PatchException("$PATCH: ${method.definingClass}->${method.name} returns a value")
    method.requireLocals(PATCH, 2)
    method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, ${method.parameterRegister(triggerIndex)}
            invoke-static { v0, v1 }, $allow
            move-result v0
            if-nez v0, :start
            return-void
        """.trimIndent(),
        ExternalLabel("start", method.getInstruction(0)),
    )
}

/** First thing in [method], the player goes to [tell]. The range form borrows no register. */
internal fun tellFirst(method: MutableMethod, tell: String) {
    method.addInstruction(0, "invoke-static/range { p0 .. p0 }, $tell")
}
