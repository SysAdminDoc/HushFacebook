/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.backgroundplay

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Where Keep playing in the background hooks, found by kept names, literals and shapes only (read
 * from 577 and 580, 2026-09-27). The obfuscated names in these comments are for reviewers; the code
 * never writes one down.
 *
 * - Facebook's own background playback, whose kept classes are the same on both builds:
 *   BackgroundPlaybackManager, an ActivityLifecycleCallbacks whose onActivityStopped holds the trace
 *   "BackgroundPlaybackManager.onBackground" and, once no Facebook window is left, asks each handler
 *   in its one List field (580 A05) whether to carry on the last recorded pause: an interface method
 *   taking the session and that record (580 LX/Uuh;->D5P(FbUserSession, LX/7SL;)Z, 577
 *   LX/V6H;->D4h(..., LX/7Er;)Z). Its onActivityStarted hands the record back to the handler that took
 *   it (580 D5T, 577 D4l). Facebook only makes handlers for a fullscreen player, behind server flags.
 * - The fullscreen handler: getTag() answers "FullscreenBackgroundPlaybackHandler" (580 LX/UMb;, 577
 *   LX/UX4;), with a public constructor that takes nothing. Its check reads the record and gives up,
 *   through one shared `return`, unless the global inline sound is on and the content is eligible
 *   (two static boolean calls; 587 LX/0Qbe;->ICY reads the sound from a boolean field of the settings
 *   object a lazy getter answers instead), the record is a pause (a boolean field), came at most 1000 ms ago (a
 *   cmp-long), wasn't a BY_USER pause and came from a FULL_SCREEN_PLAYER (two compares of the record's
 *   enum fields with a static constant), the record's story, media and media id are there (three null
 *   checks right after the calls that answer them), and the media has a title and an owner name (two
 *   null checks of the registers later handed to the notification's starter). Then it builds a new
 *   "background_playback" player from the record, seeks it to the saved position, plays it with
 *   BY_BACKGROUND_PLAY and calls the notification's starter.
 * - The record: its class has one String field, the video id (580 LX/7SL;->A06), and one field of the
 *   player's trigger type (A03).
 * - The notification's starter (580 LX/dk6;->A02, 577 LX/gLc;->A02) takes (Context, player type,
 *   PlayerOrigin, title, description, video id, three booleans), puts "EXTRA_AUDIO_PRODUCT" in the
 *   intent for PlaybackNotificationService and first asks its class's one private (Z)Z method, which
 *   reads a server flag (580 A01). The stopper, which the handler's return calls, asks it too.
 * - FbGrootPlayer (Tap to play's anchors): its play, its inner pause, and the pause's static helper
 *   (580 LX/5BR;->A0R, 577 LX/4qS;->A0Q), which records the pause through a registry method taking
 *   (player type, trigger, PlayerOrigin, params, String video id, Z, Z). The String comes from the one
 *   no-argument player method answering it (580 CLz, 577 the same shape).
 * - The picture-in-picture window's buttons (587 LX/0eBg;->A01(Z)Ljava/util/List;, read 2026-11-12):
 *   one instance method taking a boolean and answering a List, holding the reels window's
 *   "com.facebook.video.reels.pip.ACTION_SKIP_BACK" and building RemoteActions. It answers a play or
 *   pause alone for a live video, else rewind, play or pause, and forward, through two returns. Both
 *   reels window fragments, the inbox window and the window's arming ask it.
 */

internal const val BACKGROUND_PLAY = "$EXTENSION_PACKAGE/media/BackgroundPlay;"
internal const val STARTED = "$BACKGROUND_PLAY->started(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val PAUSING = "$BACKGROUND_PLAY->pausing(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val DECIDING = "$BACKGROUND_PLAY->deciding(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Z"
internal const val SKIP_CHECK = "$BACKGROUND_PLAY->skipCheck()Z"
internal const val TITLE = "$BACKGROUND_PLAY->title(Ljava/lang/String;)Ljava/lang/String;"
internal const val SUBTITLE = "$BACKGROUND_PLAY->subtitle(Ljava/lang/String;)Ljava/lang/String;"
internal const val ANSWERED = "$BACKGROUND_PLAY->answered(Z)V"
internal const val NOTIFICATION_ALLOWED = "$BACKGROUND_PLAY->notificationAllowed()Z"
internal const val RETURNING = "$BACKGROUND_PLAY->returning(Ljava/lang/Object;)V"
internal const val RETURNED = "$BACKGROUND_PLAY->returned()V"

internal const val WINDOW_ACTIONS =
    "$EXTENSION_PACKAGE/media/BackgroundPlayButton;->windowActions(Ljava/util/List;)Ljava/util/List;"

/** The reels window's rewind action, which only its builder of the window's buttons holds. */
internal const val WINDOW_SKIP_BACK = "com.facebook.video.reels.pip.ACTION_SKIP_BACK"
internal const val REMOTE_ACTION = "Landroid/app/RemoteAction;"

/**
 * The extension's stubs the patch fills in: the video id accessor's name, the name of the pause the
 * window's button calls and the handler's class.
 */
internal const val VIDEO_ID_METHOD = "videoIdMethod"
internal const val PAUSE_METHOD = "pauseMethod"
internal const val HANDLER_CLASS = "handlerClass"

internal const val MANAGER = "Lcom/facebook/video/bgplayback/manager/BackgroundPlaybackManager;"
internal const val MANAGER_ON_BACKGROUND = "BackgroundPlaybackManager.onBackground"
internal const val FULLSCREEN_HANDLER = "FullscreenBackgroundPlaybackHandler"
internal const val NOTIFICATION_SERVICE =
    "Lcom/facebook/video/bgplayback/notification/service/PlaybackNotificationService;"
internal const val AUDIO_PRODUCT = "EXTRA_AUDIO_PRODUCT"

internal const val PLAYER_ORIGIN = "Lcom/facebook/video/common/playerorigin/PlayerOrigin;"
internal const val SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
internal const val ACTIVITY = "Landroid/app/Activity;"
internal const val CONTEXT = "Landroid/content/Context;"
internal const val LIST = "Ljava/util/List;"
internal const val STRING = "Ljava/lang/String;"

/**
 * The trigger names the extension and Facebook's background player need the trigger enum to keep:
 * BY_ANDROID is the one the window's headphones button pauses the window's video with.
 */
internal val BACKGROUND_TRIGGERS = listOf("BY_BACKGROUND_PLAY", "BY_USER", "BY_ANDROID")

/**
 * The pause the window's headphones button calls (587 LX/05MK;->JTz(LX/02xv;)V): the one public pause
 * of [pauses] that takes only [trigger] and hands on to [inner]. Null unless there's exactly one.
 */
internal fun triggerPause(pauses: List<Method>, inner: Method, trigger: String): Method? =
    pauses.singleOrNull {
        it !== inner && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.parameters() == listOf(trigger) &&
            it.code().any { instruction ->
                val call = instruction.call
                call != null && call.definingClass == inner.definingClass && call.name == inner.name &&
                    call.parameters() == inner.parameters()
            }
    }

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun Method.parameters() = parameterTypes.map(CharSequence::toString)
private fun MethodReference.parameters() = parameterTypes.map(CharSequence::toString)

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private val Instruction.call: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The registers an invoke hands its callee, the receiver first for an instance call. */
private fun Instruction.arguments(): List<Int> = namedRegisters()

private val invokeStatic = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val invokeVirtual = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/**
 * The static helper that the inner [pause] of [owner] hands on to with the trigger, the player and
 * one more argument. 586 kept it in the player class; 587 moved it to a class of static helpers,
 * which [classOf] finds. Null when there isn't exactly one.
 */
internal fun pauseHelper(owner: ClassDef, pause: Method, trigger: String, classOf: (String) -> ClassDef?): Method? {
    val calls = pause.code().mapNotNull { instruction ->
        instruction.call?.takeIf {
            instruction.opcode in invokeStatic && it.returnType == "V" &&
                it.parameters().size == 3 && it.parameters()[0] == trigger && it.parameters()[1] == owner.type
        }
    }.distinctBy { it.definingClass + it.name + it.parameters() }
    val call = calls.singleOrNull() ?: return null
    val holder = if (call.definingClass == owner.type) owner else classOf(call.definingClass) ?: return null
    return holder.methods.singleOrNull {
        it.isStatic() && it.name == call.name && it.parameters() == call.parameters() && it.implementation != null
    }
}

/** Whether [reference] is the registry's recorder of a play or pause: (type, trigger, origin, params, String, Z, Z)V. */
internal fun isRecorder(reference: MethodReference, trigger: String): Boolean {
    val parameters = reference.parameters()
    return reference.returnType == "V" && parameters.size == 7 && parameters[1] == trigger &&
        parameters[2] == PLAYER_ORIGIN && parameters[4] == STRING && parameters[5] == "Z" && parameters[6] == "Z"
}

/** The indices in [helper] of its calls to the recorder. */
internal fun recorderCalls(helper: Method, trigger: String): List<Int> =
    helper.code().withIndex()
        .filter { (_, instruction) -> instruction.call?.let { isRecorder(it, trigger) } == true }
        .map { it.index }

/**
 * The name of the player's video id accessor: the one no-argument String method of [player] whose
 * answer is the only value written to the register [helper] hands the recorder at [callIndex] as
 * its video id. Null when that isn't so.
 */
internal fun videoIdAccessor(helper: Method, callIndex: Int, player: String): String? {
    val instructions = helper.implementation?.instructions?.toList() ?: return null
    val register = instructions[callIndex].arguments().getOrNull(5) ?: return null
    val writes = instructions.indices.filter { index ->
        val instruction = instructions[index]
        instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA == register
    }
    val write = writes.singleOrNull()?.takeIf { it in 1 until callIndex } ?: return null
    if (instructions[write].opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val source = instructions[write - 1]
    val call = source.call ?: return null
    if (source.opcode !in invokeVirtual || call.definingClass != player || call.parameterTypes.isNotEmpty() ||
        call.returnType != STRING
    ) {
        return null
    }
    return call.name
}

/** Whether [classDef] is Facebook's background playback manager as the extension makes and fills it. */
internal fun isBackgroundManager(classDef: ClassDef): Boolean =
    classDef.type == MANAGER &&
        classDef.methods.any {
            it.name == "<init>" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } &&
        managerStops(classDef) != null &&
        classDef.fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LIST } == 1

/** The manager's onActivityStopped, holding its "onBackground" trace. */
internal fun managerStops(manager: ClassDef): Method? = manager.methods.singleOrNull {
    it.name == "onActivityStopped" && it.parameters() == listOf(ACTIVITY) && it.returnType == "V" &&
        holdsString(it, MANAGER_ON_BACKGROUND)
}

/** The question the manager's stop asks each handler: an interface method (session, record)Z. */
internal fun handlerQuestions(stops: Method): List<MethodReference> =
    stops.code().filter {
        it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE
    }.mapNotNull { instruction ->
        instruction.call?.takeIf { it.returnType == "Z" && it.parameters().size == 2 && it.parameters()[0] == SESSION }
    }.distinctBy { it.definingClass + it.name + it.parameters() }

/** Whether [classDef] is the fullscreen handler: it answers the tag, implements [handler] and makes itself. */
internal fun isFullscreenHandler(classDef: ClassDef, handler: String): Boolean =
    handler in classDef.interfaces &&
        classDef.methods.any {
            it.name == "getTag" && it.parameterTypes.isEmpty() && it.returnType == STRING &&
                holdsString(it, FULLSCREEN_HANDLER)
        } &&
        classDef.methods.any {
            it.name == "<init>" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags)
        }

/** The handler's check of a record, (session, record)Z. */
internal fun handlerChecks(handler: ClassDef, record: String): List<Method> = handler.methods.filter {
    !it.isStatic() && it.returnType == "Z" && it.parameters() == listOf(SESSION, record) && it.implementation != null
}

/** The handler's return to Facebook, (activity, session, record)V. */
internal fun handlerReturns(handler: ClassDef, record: String): List<Method> = handler.methods.filter {
    !it.isStatic() && it.returnType == "V" && it.parameters() == listOf(ACTIVITY, SESSION, record) &&
        it.implementation != null
}

/** The instance fields of [record] of [type]. */
internal fun recordFields(record: ClassDef, type: String) =
    record.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }

/** Whether [reference] is the notification's starter: (Context, type, origin, String x3, Z x3)V. */
internal fun isNotificationStarter(reference: MethodReference): Boolean {
    val parameters = reference.parameters()
    return reference.returnType == "V" && parameters.size == 9 && parameters[0] == CONTEXT &&
        parameters[2] == PLAYER_ORIGIN && parameters.subList(3, 6).all { it == STRING } &&
        parameters.subList(6, 9).all { it == "Z" }
}

/**
 * The notification's server flag check: the one private, non-static (Z)Z method of [starterClass],
 * which [starter] calls. Null unless the starter also names the notification service and puts
 * "EXTRA_AUDIO_PRODUCT" in its intent.
 */
internal fun notificationGate(starterClass: ClassDef, starter: Method): Method? {
    if (!holdsString(starter, AUDIO_PRODUCT)) return null
    val namesService = starter.code().any {
        ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type == NOTIFICATION_SERVICE
    }
    if (!namesService) return null
    val gates = starterClass.methods.filter {
        AccessFlags.PRIVATE.isSet(it.accessFlags) && !it.isStatic() && it.returnType == "Z" &&
            it.parameters() == listOf("Z") && it.implementation != null
    }
    val gate = gates.singleOrNull() ?: return null
    val asked = starter.code().any {
        it.opcode == Opcode.INVOKE_DIRECT && it.call?.let { call -> call.definingClass == starterClass.type && call.name == gate.name } == true
    }
    return gate.takeIf { asked }
}

/** What each of the handler check's ways to its no is. */
internal enum class CheckKind {
    /**
     * A static call's boolean, or 587's settings field for the first: the global inline sound, eligible
     * content. Skipped for a video carried on.
     */
    ASKS,

    /** The record's trigger or player type against a constant. Skipped for a video carried on. */
    RECORD_ENUM,

    /** The record's pause flag. Kept. */
    RECORD_FLAG,

    /** The record's age. Kept. */
    AGE,

    /** A null check right after the call that answered it: story, media, media id. Kept. */
    FRESH_NULL,

    /** The media's title, handed to the notification. Given a plain one for a video carried on. */
    TITLE,

    /** The owner's name, the notification's second line. Given an empty one for a video carried on. */
    SUBTITLE,
}

/** How many of each kind the check holds on both builds. A build that differs stops the patch. */
internal val EXPECTED_CHECKS = mapOf(
    CheckKind.ASKS to 2, CheckKind.RECORD_ENUM to 2, CheckKind.RECORD_FLAG to 1, CheckKind.AGE to 1,
    CheckKind.FRESH_NULL to 3, CheckKind.TITLE to 1, CheckKind.SUBTITLE to 1,
)

/** The handler's check as the patch reads it. */
internal class HandlerCheck(
    /** The one `return` every way to a no goes to. */
    val reject: Int,
    /** Each branch to [reject], by index, with its kind. */
    val branches: Map<Int, CheckKind>,
    /** The index of the notification starter's call. */
    val starterCall: Int,
    val starter: MethodReference,
    /** The registers the starter call reads its title and description from. */
    val titleRegister: Int,
    val subtitleRegister: Int,
)

/**
 * Reads [check], the fullscreen handler's check of a record of type [record]: its one call to the
 * notification's starter, its shared `return` for a no, and the kind of each branch there. Throws
 * [IllegalStateException] naming what doesn't fit.
 */
internal fun readHandlerCheck(check: Method, record: String): HandlerCheck {
    val flow = ControlFlow.of(check)
    val instructions = flow.instructions
    val starterCalls = instructions.withIndex().filter { (_, it) -> it.call?.let(::isNotificationStarter) == true }
    val starterCall = starterCalls.singleOrNull()?.index
        ?: error("expected one call to the notification's starter, found ${starterCalls.size}")
    val starterArguments = instructions[starterCall].arguments()
    if (starterArguments.size != 10) error("the starter's call hands it ${starterArguments.size} registers, not 10")
    val titleRegister = starterArguments[4]
    val subtitleRegister = starterArguments[5]

    val branchOpcodes = setOf(Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LEZ, Opcode.IF_GTZ,
        Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE)
    val branches = instructions.indices.filter { instructions[it].opcode in branchOpcodes }
    val first = branches.firstOrNull() ?: error("the check has no branch")
    val reject = flow.normal[first].first()
    val rejectInstruction = instructions[reject]
    if (rejectInstruction.opcode != Opcode.RETURN) error("its first branch lands on ${rejectInstruction.opcode}, not a return")

    val kinds = linkedMapOf<Int, CheckKind>()
    for (branch in branches) {
        if (flow.normal[branch].first() != reject) continue
        if (branch < 2) error("a branch at $branch has nothing before it to read")
        val instruction = instructions[branch]
        val previous = instructions[branch - 1]
        val before = instructions[branch - 2]
        val tested = instruction.namedRegisters()
        fun writes(it: Instruction, register: Int) =
            it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
        fun readsRecord(it: Instruction) =
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass == record
        val kind = when {
            instruction.opcode == Opcode.IF_EQZ && previous.opcode == Opcode.MOVE_RESULT && writes(previous, tested[0]) &&
                before.opcode in invokeStatic && before.call?.returnType == "Z" -> CheckKind.ASKS
            (instruction.opcode == Opcode.IF_EQ || instruction.opcode == Opcode.IF_NE) &&
                previous.opcode == Opcode.SGET_OBJECT && tested.any { writes(previous, it) } &&
                before.opcode == Opcode.IGET_OBJECT && readsRecord(before) && tested.any { writes(before, it) } &&
                tested.distinct().size == 2 -> CheckKind.RECORD_ENUM
            (instruction.opcode == Opcode.IF_NEZ || instruction.opcode == Opcode.IF_EQZ) &&
                previous.opcode == Opcode.IGET_BOOLEAN && readsRecord(previous) && writes(previous, tested[0]) ->
                CheckKind.RECORD_FLAG
            // 587 reads the inline sound from a field of the settings object a lazy getter hands back.
            instruction.opcode == Opcode.IF_EQZ && previous.opcode == Opcode.IGET_BOOLEAN && !readsRecord(previous) &&
                writes(previous, tested[0]) -> CheckKind.ASKS
            previous.opcode == Opcode.CMP_LONG && writes(previous, tested[0]) -> CheckKind.AGE
            instruction.opcode == Opcode.IF_EQZ && previous.opcode == Opcode.MOVE_RESULT_OBJECT &&
                writes(previous, tested[0]) -> CheckKind.FRESH_NULL
            instruction.opcode == Opcode.IF_EQZ && tested[0] == titleRegister && branch < starterCall -> CheckKind.TITLE
            instruction.opcode == Opcode.IF_EQZ && tested[0] == subtitleRegister && branch < starterCall ->
                CheckKind.SUBTITLE
            else -> error("a branch to its no at $branch (${instruction.opcode} after ${previous.opcode}) is none it knows")
        }
        kinds[branch] = kind
    }
    val counts = CheckKind.values().associateWith { kind -> kinds.values.count { it == kind } }
    if (counts != EXPECTED_CHECKS) error("its ways to a no are $counts, expected $EXPECTED_CHECKS")
    if (kinds.keys.any { it > starterCall }) error("a way to a no comes after the notification's starter")
    return HandlerCheck(reject, kinds, starterCall, instructions[starterCall].call!!, titleRegister, subtitleRegister)
}

/**
 * Whether [method] builds the picture-in-picture window's buttons: an instance (Z)List method that
 * holds the reels window's rewind action and makes a RemoteAction.
 */
internal fun isWindowActionBuilder(method: Method): Boolean =
    !method.isStatic() && method.returnType == LIST && method.parameters() == listOf("Z") &&
        method.implementation != null && holdsString(method, WINDOW_SKIP_BACK) &&
        method.code().any {
            it.opcode == Opcode.NEW_INSTANCE && ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type == REMOTE_ACTION
        }

/** The indices of [method]'s `return-object`s. */
internal fun objectReturns(method: Method): List<Int> =
    method.code().withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }

/** A type descriptor as a binary class name: `La/b/C;` is `a.b.C`. */
internal fun binaryName(type: String): String = type.removePrefix("L").removeSuffix(";").replace('/', '.')
