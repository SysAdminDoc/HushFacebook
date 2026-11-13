/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.feedsound

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep feed videos muted"
internal const val FEED_SOUND = "$EXTENSION_PACKAGE/media/FeedSound;"
internal const val OWN_SESSION_SOUND = "$FEED_SOUND->sessionSound(Z)Z"
internal const val OWN_SOUND_CHANGE = "$FEED_SOUND->soundChange(Ljava/lang/Object;ZZ)Z"
internal const val OWN_SOUND_SET = "$FEED_SOUND->soundSet(Ljava/lang/Object;Ljava/lang/Object;Z)V"

/** The feed sound state's session start, a name Facebook keeps. */
internal const val NEW_SESSION = "startNewSession"

/**
 * Keeps videos in the feed muted until the user turns their sound on (a Reddit request,
 * 2026-10-21). Facebook holds the feed's sound in one flag on a sound state, the one class with a
 * [NEW_SESSION] method. Each new session, as Facebook comes back after a while away, works that
 * flag out again from Facebook's own sound setting and the headphones, so a sound the user had
 * muted plays again. Ringer changes, other apps' audio ending and the state's own recomputes then
 * turn it on through the state's setter that takes a reason.
 *
 * Both setters ask the extension's FeedSound first: the session setter's flag goes to
 * sessionSound before anything reads it, and the reasoned setter's flag goes to soundChange with
 * its reason and the flag the state holds now, right after the setter reads that. A tap on a
 * video's speaker, the volume up key and every mute pass as Facebook sent them.
 *
 * Then both hand soundSet the state, the reason and the flag they set. A session starts only on a
 * cold start or after a long time away, so a sound turned on stayed on across a quick trip Home
 * (#465): when Facebook comes back with the flag on, the extension calls the state's own
 * [NEW_SESSION], and the session setter's ask mutes it.
 *
 * Its Mute stories switch (#261) asks the extension's StorySound for each story video's volume in
 * the story viewer's volume setter ([storyVolume]), which works its answer out apart from the
 * feed's flag on most builds.
 *
 * Its Restore feed sound after opened videos switch (#350) asks the extension's FeedSound for the
 * time an opened video played with its sound, in the step that runs as it closes
 * ([viewerExitSound]). Past a threshold from Facebook's servers, that step turns the feed's sound on
 * as the user's own pick, so a feed muted before the video comes back loud.
 *
 * In the default selection with its switch off: a feed that stays quiet after the user turned
 * its sound on would be a surprise nobody asked for.
 */
@Suppress("unused")
val keepFeedVideosMutedPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Keep feed videos muted",
    description = "Feed videos start with the sound off every time you open Facebook, and only a tap on a " +
        "video's speaker or the volume up key turns it on. It can also keep a long watch of a video you open from " +
        "turning the feed's sound on. Starts off. Turn it on in Hushfacebook settings > Video and audio.",
) {
    category("Playback")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        askFeedSound(feedSoundState())
        // Mute stories (#261) asks in the story viewer's volume setter. A moved story viewer
        // costs that switch, not the feed's.
        try {
            askStorySound(storyVolume())
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without muting stories.")
        }
        // Restore feed sound after opened videos (#350) asks in the opened video's close step. A
        // moved close step costs that switch, not the feed's.
        try {
            askViewerSoundTime(viewerExitSound())
        } catch (moved: PatchException) {
            patchLog.warning("${moved.message}. The patch goes on without restoring the feed's sound after opened videos.")
        }
        enableStatus("keepFeedVideosMuted")
    }
}

/**
 * Facebook's feed sound state [owner], whose sound flag is [field]. Its [session] setter takes the
 * flag a new session works out in [sessionFlag]. Its [change] setter takes a reason in [reason]
 * and a flag in [changeFlag], and its first instruction reads the flag the state holds into
 * [current], which the second compares with [changeFlag].
 */
internal class FeedSoundState(
    val owner: String,
    val field: FieldReference,
    val session: Method,
    val sessionFlag: Int,
    val change: Method,
    val reason: Int,
    val changeFlag: Int,
    val current: Int,
    val askAt: Int,
)

private fun same(a: Method, b: MethodReference) = a.name == b.name && a.returnType == b.returnType &&
    a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

private fun same(a: Method, b: Method) = a.name == b.name && a.returnType == b.returnType &&
    a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

internal fun startsSession(method: Method) = method.name == NEW_SESSION && method.returnType == "V" &&
    method.parameterTypes.isEmpty() && method.implementation != null

private fun stores(instruction: Instruction, field: FieldReference) =
    instruction.opcode == Opcode.IPUT_BOOLEAN && (instruction as ReferenceInstruction).reference == field

/**
 * The sound state in [owner]: [NEW_SESSION] hands its flag to one setter of its own taking a
 * reason and a flag, and that reason's type also goes, with a String and a flag, to one other
 * setter. Both store the flag the reasoned setter reads first. Throws for any other shape.
 */
internal fun feedSoundState(owner: ClassDef): FeedSoundState {
    val type = owner.type
    val start = owner.methods.single(::startsSession)
    val handed = start.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.definingClass == type && it.returnType == "V" && it.parameterTypes.size == 2 && it.parameterTypes[1].toString() == "Z" }
    val called = handed.singleOrNull()
        ?: throw PatchException("$PATCH: $type->$NEW_SESSION hands its flag to ${handed.size} setters")
    val session = owner.methods.singleOrNull { same(it, called) && it.implementation != null }
        ?: throw PatchException("$PATCH: $type has no body for $called")
    val reasonType = called.parameterTypes[0].toString()
    val changes = owner.methods.filter {
        it.returnType == "V" && it.implementation != null &&
            it.parameterTypes.map(CharSequence::toString) == listOf(reasonType, "Ljava/lang/String;", "Z")
    }
    val change = changes.singleOrNull()
        ?: throw PatchException("$PATCH: $type has ${changes.size} sound setters taking a reason")
    val where = "$type->${change.name}"

    // The reasoned setter reads the flag it holds and compares it with the one it's asked for, first.
    // Up to 584 the read is the first instruction. 585 puts a counter bump for the unmute reason in
    // front of it (a branch lands on the read), so it's the first read of the state's own boolean
    // field whose very next instruction compares it, not the first instruction.
    val code = change.implementation!!.instructions.toList()
    val self = change.localRegisterCount()
    val reason = self + 1
    val changeFlag = self + 3
    val readAt = code.indices.firstOrNull { at ->
        val candidate = code[at] as? TwoRegisterInstruction
        val candidateField = (candidate as? ReferenceInstruction)?.reference as? FieldReference
        val next = code.getOrNull(at + 1) as? TwoRegisterInstruction
        candidate != null && candidate.opcode == Opcode.IGET_BOOLEAN && candidate.registerB == self &&
            candidateField?.definingClass == type && next != null &&
            (next.opcode == Opcode.IF_EQ || next.opcode == Opcode.IF_NE) &&
            setOf(next.registerA, next.registerB) == setOf(candidate.registerA, changeFlag)
    } ?: throw PatchException("$PATCH: $where doesn't read its sound flag and compare it with the one it's asked for")
    val read = code[readAt] as TwoRegisterInstruction
    val field = (read as ReferenceInstruction).reference as FieldReference
    val current = read.registerA
    val compare = code[readAt + 1] as TwoRegisterInstruction
    if (compare == null || (compare.opcode != Opcode.IF_EQ && compare.opcode != Opcode.IF_NE) ||
        setOf(compare.registerA, compare.registerB) != setOf(current, changeFlag)
    ) {
        throw PatchException("$PATCH: $where doesn't compare its sound flag with the one it's asked for")
    }
    if (code.none { stores(it, field) && (it as TwoRegisterInstruction).registerA == changeFlag }) {
        throw PatchException("$PATCH: $where doesn't store the flag it's asked for")
    }
    if (session.implementation!!.instructions.none { stores(it, field) }) {
        throw PatchException("$PATCH: $type->${session.name} doesn't store the sound flag")
    }

    // The ask goes in between the read and the comparison, so nothing may jump to the comparison.
    val flow = ControlFlow.of(change)
    for (index in code.indices) {
        if (index != readAt && (readAt + 1) in flow.normal[index] + flow.exceptional[index]) {
            throw PatchException("$PATCH: $where has a jump to its sound flag's comparison")
        }
    }
    if (maxOf(reason, changeFlag, current) >= 16) throw PatchException("$PATCH: $where has registers past v15")
    return FeedSoundState(type, field, session, session.localRegisterCount() + 2, change, reason, changeFlag, current, readAt + 1)
}

/** The one sound state outside the extension. Throws when there's none, or more than one. */
internal fun BytecodePatchContext.feedSoundState(): FeedSoundState {
    val owners = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
        if (classDef.methods.any(::startsSession)) owners += classDef
    }
    val owner = owners.singleOrNull()
        ?: throw PatchException("$PATCH: found ${owners.size} classes with a $NEW_SESSION method, expected one")
    return feedSoundState(owner)
}

/**
 * Asks FeedSound for the session setter's flag before anything reads it, and for the reasoned
 * setter's right after that setter reads the flag the state holds. Each answer goes back in the
 * flag's own register, so the rest of both methods runs as Facebook wrote it, and then goes to
 * soundSet with the state and the reason.
 */
internal fun BytecodePatchContext.askFeedSound(state: FeedSoundState) {
    val owner = mutableClassDefBy(state.owner)
    // The reasoned setter's registers all sit under v16 (feedSoundState checks), its own just under its reason's.
    val changeSelf = state.reason - 1
    owner.methods.single { same(it, state.change) }.addInstructions(
        state.askAt,
        """
            invoke-static { v${state.reason}, v${state.changeFlag}, v${state.current} }, $OWN_SOUND_CHANGE
            move-result v${state.changeFlag}
            invoke-static { v$changeSelf, v${state.reason}, v${state.changeFlag} }, $OWN_SOUND_SET
        """,
    )
    // The session setter's own, its reason and its flag are its three parameters, in a row.
    val sessionSelf = state.sessionFlag - 2
    owner.methods.single { same(it, state.session) }.addInstructions(
        0,
        """
            invoke-static/range { v${state.sessionFlag} .. v${state.sessionFlag} }, $OWN_SESSION_SOUND
            move-result v${state.sessionFlag}
            invoke-static/range { v$sessionSelf .. v${state.sessionFlag} }, $OWN_SOUND_SET
        """,
    )
}
