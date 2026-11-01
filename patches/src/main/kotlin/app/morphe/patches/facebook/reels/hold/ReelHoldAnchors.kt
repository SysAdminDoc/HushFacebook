/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.hold

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.media.taptoplay.FB_USER_SESSION
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Where Hold a reel for 2x hooks, found by kept names only (read from 577 and 580, 2026-09-29). The
 * obfuscated names in these comments are for reviewers; the code never writes one down.
 *
 * Facebook's Reels controls (FbShortsVideoControlComponent, 580 LX/83J;, 577 LX/7mG;, and
 * FbShortsViewerOverlayComponent, 580 LX/83D;, 577 LX/7H5;) already speed a reel up while it's held,
 * behind server flags most accounts don't get:
 *
 * - The long-press handlers: Kotlin lambdas keeping their captured names, among them
 *   $shouldDispatchLongPressEventDirectly and $immersiveFeedPlayerConfig, and holding the log
 *   string "speed_up" (580 LX/88x; and LX/UoS;, 577 LX/9bg; and LX/V0J;). Each asks the immersive
 *   player config (580 LX/Cv5;, 577 LX/Cpk;) one flag with no arguments (580 AgW, 577 Aiu), the
 *   speed-up flag. Yes, and a press on a reel that isn't an ad goes to the speed-up when it landed
 *   within the config's edge width of the reel's left or right side; otherwise it opens the
 *   long-press menu. Each loads "speed_up" once, for the log call it makes only on its way to the
 *   speed-up, past those checks (580 88x index 125, UoS 117; 577 9bg 131, V0J 107). The speed-up
 *   (580 LX/83J;->A0H, 577 LX/7mG;->A0H) remembers the reel's speed and sets the config's
 *   long-press speed, 2x unless the server says otherwise, through FbGrootPlayer's speed setter.
 * - The edge check: the one method of the build taking (MotionEvent, View, FbUserSession, the
 *   config, boolean) and answering a boolean (581 LX/881;->A08), an instance method of ten
 *   registers on all three. One long-press handler calls it itself, the other through a lazy value.
 *   It measures the press's getX() against the view's width, so the right-edge switch measures the
 *   same two. 583 has no such method: Redex inlined it into a merged Kotlin lambda the handler makes
 *   (LX/CnX;, case 4 of its invoke()), which reads the press and the view off the handler's gesture
 *   (LX/D6k;) and answers Boolean.valueOf of the same measure, and the handler reads it as a lazy
 *   value.
 * - The release listeners: lambdas keeping $isInLongPress2xPlaybackSpeed and
 *   $immersiveFeedPlayerConfig (580 LX/88y; and LX/UoP;, 577 LX/9bh; and LX/V0G;), a reel's touch
 *   listener. Each asks a second flag with no arguments (580 AgX, 577 Aiv) and, straight after its
 *   branch, the speed-up flag; with both yes, on the finger's lift or a cancel it puts back the
 *   speed the speed-up remembered, whenever the player's speed differs from it. It hears every
 *   touch on the reel, not only a hold's. It captures whether a hold is on and the speed to put
 *   back when the reel is drawn, and the speed-up has the reel drawn again, so a lift before that
 *   reaches a listener holding the old values. Its own check of the hold flag is behind a third
 *   config answer (580 AhA, 577 AjY) that every implementation on both builds answers no, so on
 *   these builds every listener asks both flags on every touch.
 * - 583 dropped UddPlayerControlComponent (582 LX/XIg;) and its long-press handler and release
 *   listener (582 LX/Z4h; and LX/Z4d;), so there's one of each, FbShortsVideoControlComponent's.
 * - The hold speed: the speed-ups in FbShortsVideoControlComponent (580 LX/83J;->A0H) and, up to
 *   582, UddPlayerControlComponent (580 LX/T9a;->A05) set FbGrootPlayer's speed to a double they read
 *   from the object the config's no-argument CIr() answers (580 LX/4Xa;->A0R), made a float. The
 *   same read gives the speed the helper that remembers the pre-hold speed compares with, and a
 *   2x label. Outside the Video tab it answers a fixed 2.0; where an account's Reels live in the
 *   Video tab (the object's surface is FB_SHORTS_IN_WATCH_TAB) it answers MobileConfig double
 *   0x104003303ee0017, whose default no code sets.
 * - The speed to put back: the speed-up asks a helper (580 LX/B1k;->A00) for it, which answers the
 *   player's speed through its getter (580 Brl, 577 BtK) but normal speed when that already is the
 *   hold speed and MobileConfig 0x101055200213585 says so, and keeps it in the component's state, from
 *   which the listener is drawn with it. So a reel Keep the reel speed started at 2x comes back at
 *   normal speed, and so does a reel whose listener was drawn before a speed was picked. The release
 *   listeners each read the getter to compare with it, the one no-argument float method of the
 *   player they call; the extension reads the player's speed through it when a hold speeds a reel up.
 * - The overlay component's render (580 LX/83D;->A1F, 577 LX/7H5;->A1N) gives a reel its release
 *   listener only when the speed-up flag says yes. The control component gives it one regardless.
 *   Three other places read the speed-up flag (a Watch fragment's setup and an auto-advance guard);
 *   they don't touch the hold and are left alone.
 */

internal const val REEL_HOLD = "$EXTENSION_PACKAGE/reels/ReelHold;"
internal const val TOUCH = "$REEL_HOLD->touch(Landroid/view/MotionEvent;)V"
internal const val LONG_PRESS = "$REEL_HOLD->longPress(Z)Z"
internal const val ANYWHERE = "$REEL_HOLD->anywhere(Z)Z"
internal const val EDGE_TOUCH = "$REEL_HOLD->edgeTouch(Landroid/view/MotionEvent;Landroid/view/View;)V"
internal const val SPEED_UP = "$REEL_HOLD->speedUp(Z)Z"
internal const val RELEASE = "$REEL_HOLD->release(Z)Z"
internal const val HELD = "$REEL_HOLD->held()V"
internal const val HOLD_SPEED = "$REEL_HOLD->holdSpeed(D)D"
internal const val SPEED_SET = "$REEL_HOLD->speedSet(Ljava/lang/Object;F)F"
internal const val PLAYER_SPEED_STUB = "playerSpeed"

internal const val SPEED_UP_LOG = "speed_up"
internal const val CONFIG_FIELD = "\$immersiveFeedPlayerConfig"
internal const val DISPATCH_DIRECTLY_FIELD = "\$shouldDispatchLongPressEventDirectly"
internal const val IN_LONG_PRESS_FIELD = "\$isInLongPress2xPlaybackSpeed"
internal val CONTROL_COMPONENTS = listOf("FbShortsVideoControlComponent", "FbShortsViewerOverlayComponent")
internal val SPEED_UP_COMPONENTS = listOf("FbShortsVideoControlComponent")

private const val VIEW = "Landroid/view/View;"

private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

private val Method.code get() = implementation?.instructions?.toList().orEmpty()

/** The type of [classDef]'s captured immersive player config, or null when it captures none. */
internal fun configType(classDef: ClassDef): String? =
    classDef.fields.singleOrNull { it.name == CONFIG_FIELD && !AccessFlags.STATIC.isSet(it.accessFlags) }?.type

private fun ClassDef.hasBooleanField(name: String) =
    fields.any { it.name == name && it.type == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags) }

/** Whether [classDef] is a long-press handler: its captured names, and a method logging "speed_up". */
internal fun isLongPressHandler(classDef: ClassDef): Boolean =
    classDef.hasBooleanField(DISPATCH_DIRECTLY_FIELD) && configType(classDef) != null &&
        classDef.methods.any { holdsString(it, SPEED_UP_LOG) }

/** Whether [classDef] is a release listener: it captures whether a hold is on and the config. */
internal fun isReleaseListener(classDef: ClassDef): Boolean =
    classDef.hasBooleanField(IN_LONG_PRESS_FIELD) && configType(classDef) != null

/** The call in [method] at each index, when it asks [config] a flag: no arguments, a boolean answer. */
internal fun flagCalls(method: Method, config: String): Map<Int, String> =
    method.code.withIndex().mapNotNull { (index, instruction) ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) {
            return@mapNotNull null
        }
        if (call.definingClass != config || call.returnType != "Z" || call.parameterTypes.isNotEmpty()) return@mapNotNull null
        index to call.name
    }.toMap()

/**
 * The flags [method] asks right before [speedUp]: a flag call, its move-result, one branch on it,
 * and then the [speedUp] call, which is how each release listener asks its second flag.
 */
internal fun flagsBefore(method: Method, config: String, speedUp: String): Set<String> {
    val code = method.code
    val calls = flagCalls(method, config)
    return calls.filter { (index, name) ->
        name != speedUp && calls[index + 3] == speedUp && code.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT &&
            code.getOrNull(index + 2)?.opcode?.name?.startsWith("if-") == true
    }.values.toSet()
}

/** Whether [method] makes an instance of one of [types]. */
internal fun makesOneOf(method: Method, types: Set<String>): Boolean = method.code.any {
    it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type in types
}

/** Whether [parameters] and [returnType] are the edge check's: (MotionEvent, View, FbUserSession, [config], Z)Z. */
internal fun isEdgeCheckShape(parameters: List<String>, returnType: String, config: String): Boolean =
    returnType == "Z" && parameters == listOf(MOTION_EVENT, VIEW, FB_USER_SESSION, config, "Z")

/** The methods of the edge check's shape that [method] calls, as references. */
internal fun edgeChecksCalled(method: Method, config: String): List<MethodReference> = method.code.mapNotNull {
    val call = (it as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
    call.takeIf { isEdgeCheckShape(call.parameterTypes.map(CharSequence::toString), call.returnType, config) }
}

/** Whether [method] is the edge check a call to [reference] names, with a body. */
internal fun isMethod(method: Method, reference: MethodReference): Boolean =
    method.definingClass == reference.definingClass && method.name == reference.name &&
        method.returnType == reference.returnType && method.parameters() == reference.parameterTypes.map(CharSequence::toString) &&
        method.implementation != null

/**
 * The edge check's code: the method it's in ([method]), the index where its press and the reel's
 * view are first in hand ([entry]), their registers ([press], [view]), and the index of each
 * instruction its boolean answer goes out by, with the answer's register ([answers]).
 */
internal class EdgeCheck(val method: Method, val entry: Int, val press: Int, val view: Int, val answers: Map<Int, Int>)

/**
 * The edge check as a method of its own, up to 582: its first two parameters are the press and the
 * view, and each `return` hands out its answer. Null when it never returns.
 */
internal fun ownEdgeCheck(method: Method): EdgeCheck? {
    val implementation = method.implementation ?: return null
    val returns = method.code.withIndex().filter { it.value.opcode == Opcode.RETURN }
        .associate { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) return null
    val press = implementation.registerCount - method.parameters().sumOf { if (it == "J" || it == "D") 2 else 1 }
    return EdgeCheck(method, 0, press, press + 1, returns)
}

/** The types [method] makes instances of. */
internal fun typesMade(method: Method): Set<String> = method.code.filter { it.opcode == Opcode.NEW_INSTANCE }
    .map { ((it as ReferenceInstruction).reference as TypeReference).type }.toSet()

/** Whether [instruction] writes [register], or a wide pair holding it. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    val destination = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    if (!instruction.opcode.setsRegister()) return false
    return destination == register || (instruction.opcode.setsWideRegister() && destination + 1 == register)
}

/**
 * The edge check where 583 inlined it into [method], a lambda's: the one read of a MotionEvent field
 * and the one read of a View field, one after the other and reached only that way, and from there on
 * a getX() of that press, a getWidth() of that view and a no-argument int asked of [config], with
 * neither register written again. Each way out returns the Boolean.valueOf it boxes the answer with
 * right away, and the answer goes out at each of those valueOf calls, all past the reads. Null
 * unless all that holds, and the two registers fit a call to the extension (neighbours, or both v15
 * or below).
 */
internal fun inlinedEdgeCheck(method: Method, config: String): EdgeCheck? {
    val code = method.code
    fun reads(index: Int, type: String) = code[index].opcode == Opcode.IGET_OBJECT &&
        ((code[index] as ReferenceInstruction).reference as FieldReference).type == type
    val pressAt = code.indices.filter { reads(it, MOTION_EVENT) }.singleOrNull() ?: return null
    val viewAt = code.indices.filter { reads(it, VIEW) }.singleOrNull() ?: return null
    if (maxOf(pressAt, viewAt) - minOf(pressAt, viewAt) != 1) return null
    val entry = maxOf(pressAt, viewAt) + 1
    val press = (code[pressAt] as TwoRegisterInstruction).registerA
    val view = (code[viewAt] as TwoRegisterInstruction).registerA
    if (press == view || entry >= code.size || view != press + 1 && (press > 15 || view > 15)) return null
    val flow = ControlFlow.of(method)
    if (code.indices.any { (it != entry - 1 && entry in flow.normal[it]) || (it != entry - 2 && entry - 1 in flow.normal[it]) }) return null
    val reached = sortedSetOf(entry)
    val queue = ArrayDeque(listOf(entry))
    while (queue.isNotEmpty()) flow.normal[queue.removeFirst()].forEach { if (reached.add(it)) queue += it }
    fun calls(index: Int, reference: String, on: Int? = null): Boolean {
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        val key = "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"
        return code[index].opcode.name.startsWith("invoke") && key == reference &&
            (on == null || (code[index] as? FiveRegisterInstruction)?.registerC == on)
    }
    val asksEdge = reached.any { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        code[index].opcode.name.startsWith("invoke-interface") && call?.definingClass == config && call.returnType == "I" &&
            call.parameterTypes.isEmpty()
    }
    if (!asksEdge || reached.none { calls(it, "$MOTION_EVENT->getX()F", press) } ||
        reached.none { calls(it, "$VIEW->getWidth()I", view) } ||
        reached.any { writes(code[it], press) || writes(code[it], view) }
    ) {
        return null
    }
    val boxes = reached.filter { calls(it, "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;") }
    val returns = reached.filter { code[it].opcode.name.startsWith("return") }
    fun boxedReturn(box: Int): Boolean {
        val taken = code.getOrNull(box + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction ?: return false
        val out = code.getOrNull(box + 2)?.takeIf { it.opcode == Opcode.RETURN_OBJECT } as? OneRegisterInstruction ?: return false
        return out.registerA == taken.registerA && code.indices.none { it != box && box + 1 in flow.normal[it] } &&
            code.indices.none { it != box + 1 && box + 2 in flow.normal[it] }
    }
    if (boxes.isEmpty() || boxes.any { it < entry } || !boxes.all(::boxedReturn) || returns.toSet() != boxes.map { it + 2 }.toSet()) {
        return null
    }
    return EdgeCheck(method, entry, press, view, boxes.associateWith { (code[it] as FiveRegisterInstruction).registerC })
}

/** The indices in [method] of the instructions loading the long-press handlers' "speed_up" log name. */
internal fun speedUpLoads(method: Method): List<Int> = method.code.withIndex().filter { (_, instruction) ->
    ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == SPEED_UP_LOG
}.map { it.index }

private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/**
 * The hold speed reads in [method]: a no-argument double method of one of [configAnswers], whose
 * answer the next instructions take, make a float and hand to [setter] ("class->name(F)V") as its
 * speed.
 */
internal fun holdSpeedReads(method: Method, setter: String, configAnswers: Set<String>): List<MethodReference> {
    val code = method.code
    return code.indices.mapNotNull { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        if ("${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}" != setter) {
            return@mapNotNull null
        }
        val speed = code[index].registers().getOrNull(1) ?: return@mapNotNull null
        val convert = code.getOrNull(index - 1)?.takeIf { it.opcode == Opcode.DOUBLE_TO_FLOAT } as? TwoRegisterInstruction
        if (convert == null || convert.registerA != speed) return@mapNotNull null
        val taken = code.getOrNull(index - 2)?.takeIf { it.opcode == Opcode.MOVE_RESULT_WIDE } as? OneRegisterInstruction
        if (taken == null || taken.registerA != convert.registerB) return@mapNotNull null
        val read = (code.getOrNull(index - 3) as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        read.takeIf { it.returnType == "D" && it.parameterTypes.isEmpty() && it.definingClass in configAnswers }
    }
}

/** The no-argument float methods of [player] that [method] calls, as references: in a release listener, the speed getter. */
internal fun speedGettersCalled(method: Method, player: String): List<MethodReference> = method.code.mapNotNull {
    val call = (it as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
    call.takeIf { call.definingClass == player && call.returnType == "F" && call.parameterTypes.isEmpty() }
}

/** Whether the instruction after the call at [index] in [method] takes its boolean answer. */
internal fun answerTakenAt(method: Method, index: Int): Int? =
    (method.code.getOrNull(index + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction)?.registerA

/*
 * Hold a feed video for 2x (issue #229), the second switch of Hold a reel for 2x. FbGrootPlayer
 * attaches its view to a container in one static method holding [ATTACH_VIEW_LOG], taking the
 * container first and the player as one other parameter (584: LX/55R;->A0O, the player third),
 * and keeps the container in its one ViewGroup field, which the attach writes (584: A06). The
 * extension's FeedVideoHold notes each player there, reads its container through that field to find
 * the video under a hold, and sets its speed with the speed setter.
 */

internal const val FEED_VIDEO_HOLD = "$EXTENSION_PACKAGE/reels/FeedVideoHold;"
internal const val FEED_TOUCH = "$FEED_VIDEO_HOLD->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)V"
internal const val FEED_ATTACHED = "$FEED_VIDEO_HOLD->attached(Landroid/view/ViewGroup;Ljava/lang/Object;)V"
internal const val PLAYER_VIEW_STUB = "playerView"
internal const val SET_PLAYER_SPEED_STUB = "setPlayerSpeed"
internal const val ATTACH_VIEW_LOG = "FbGrootPlayer.attachPlayerViewInternal"
internal const val VIEW_GROUP = "Landroid/view/ViewGroup;"

/**
 * When [method] is FbGrootPlayer's attach of its view, static, holding [ATTACH_VIEW_LOG], taking a
 * ViewGroup first and [player] once: the index of the player's parameter. Otherwise null.
 */
internal fun attachedPlayerParameter(method: Method, player: String): Int? {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || !holdsString(method, ATTACH_VIEW_LOG)) return null
    val parameters = method.parameters()
    if (parameters.firstOrNull() != VIEW_GROUP) return null
    return parameters.withIndex().filter { it.value == player }.singleOrNull()?.index
}

/**
 * The ViewGroup fields of [player] that [method] writes: in the attach, the player's view container.
 * 585 moved the write into the one static [player] method the attach hands its work to, so the
 * [player] methods [method] calls directly are read too.
 */
internal fun containersWritten(method: Method, player: ClassDef): List<FieldReference> =
    (method.code + playerCallees(player, method).flatMap { it.code })
        .filter { it.opcode == Opcode.IPUT_OBJECT }
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        .filter { it.definingClass == player.type && it.type == VIEW_GROUP }
        .distinctBy { it.toString() }

/** The methods of [player] with a body that [method] calls directly (one level), other than [method]. */
internal fun playerCallees(player: ClassDef, method: Method): List<Method> {
    val called = method.code.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.definingClass == player.type }
        .map { call -> Triple(call.name, call.parameterTypes.map(CharSequence::toString), call.returnType) }
        .toSet()
    return player.methods.filter {
        it.implementation != null && it != method && Triple(it.name, it.parameters(), it.returnType) in called
    }
}

/*
 * Hide the pull down to lock hint (issue #289), the third switch of Hold a reel for 2x. The pill a
 * video held for 2x shows is a Litho component made with its state, an enum whose constants keep
 * their names (584: LX/Txp;), its speed and a flag, in one constructor (584: LX/TbY;-><init>(Txp,F,Z)).
 * The patch hands the state to the extension's LockHint first in that constructor, which answers
 * NORMAL, the plain 2x pill, in place of the hints while the switch is on.
 */

internal const val LOCK_HINT_CLASS = "$EXTENSION_PACKAGE/reels/LockHint;"
internal const val LOCK_HINT_PILL = "$LOCK_HINT_CLASS->pill(Ljava/lang/Enum;)Ljava/lang/Enum;"

/**
 * Hide the 2x popups (#289) asks this first in both pills' renders, and a yes returns no component.
 * A KComponent's render may answer null, which lays out as nothing. The hold, its lock, the overlay
 * Facebook hides for it and the pill holder's animation all run outside the render.
 */
internal const val HIDES_2X_PILL = "$LOCK_HINT_CLASS->hidesPill()Z"
private const val COMPONENT_RENDER = "render"

/** The render of the Litho component [classDef]: an instance method by that kept name with one parameter, answering an object. */
internal fun pillRender(classDef: ClassDef): Method? = classDef.methods.singleOrNull {
    it.name == COMPONENT_RENDER && !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null &&
        it.parameterTypes.size == 1 && it.returnType.startsWith("L")
}

internal val PILL_STATES = listOf(
    "NORMAL", "LOCK_HINT", "READY_TO_LOCK", "LOCKED", "UNLOCK_HINT", "READY_TO_UNLOCK", "NUX_PROMPT",
)

/** Whether [classDef] is the 2x pill's state enum: an enum whose static initializer names every one of [PILL_STATES]. */
internal fun isPillStates(classDef: ClassDef): Boolean {
    if (classDef.superclass != "Ljava/lang/Enum;") return false
    val init = classDef.methods.singleOrNull { it.name == "<clinit>" } ?: return false
    return PILL_STATES.all { holdsString(init, it) }
}

/** Whether [method] is the 2x pill's constructor: an instance constructor taking [states], a float and a boolean. */
internal fun isPillConstructor(method: Method, states: String): Boolean =
    method.name == "<init>" && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.map(CharSequence::toString) == listOf(states, "F", "Z")

/*
 * Reels draw their own 2x pill (584: LX/TZK;), made in one place with the session, its state enum
 * (584: LX/91l;, constants named below), the video's state and two flags. The first flag (584: TZK.A03,
 * from LX/8N6;->A0I) says whether the pill speaks in hints at all: on, a held reel reads "Pull down
 * to lock 2x speed" (0x7f146209) or "Slide up to lock 2x speed" (0x7f146e56, picked by the second
 * flag); off, the render skips to the plain speed (0x7f145c59, "2x"), as Facebook draws it where it
 * has no lock. The patch hands the state and the first flag to LockHint.reelPill first in the
 * constructor.
 */
internal const val LOCK_HINT_REEL_PILL = "$LOCK_HINT_CLASS->reelPill(Ljava/lang/Enum;Z)Z"
internal val REEL_PILL_STATES = listOf(
    "LOCKED_WITH_FOOTER", "INITIATING_LOCK", "INITIATING_RESTORE", "UNLOCKED_ORIGINAL_SPEED", "UNLOCKED_2X_SPEED",
    "ORIGINAL_SPEED",
)

/** Whether [classDef] is the Reels 2x pill's state enum: an enum whose static initializer names every one of [REEL_PILL_STATES]. */
internal fun isReelPillStates(classDef: ClassDef): Boolean {
    if (classDef.superclass != "Ljava/lang/Enum;") return false
    val init = classDef.methods.singleOrNull { it.name == "<clinit>" } ?: return false
    return REEL_PILL_STATES.all { holdsString(init, it) }
}

/**
 * Whether [method] is the Reels 2x pill's constructor: an instance constructor taking the session,
 * [states], one more object and two booleans, the first being the hints flag.
 */
internal fun isReelPillConstructor(method: Method, states: String): Boolean {
    if (method.name != "<init>" || AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val parameters = method.parameterTypes.map(CharSequence::toString)
    return parameters.size == 5 && parameters[0] == FB_USER_SESSION && parameters[1] == states &&
        parameters[2].startsWith("L") && parameters[3] == "Z" && parameters[4] == "Z"
}
