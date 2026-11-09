/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.ads.sponsoredsearch.enumConstantFields
import app.morphe.patches.facebook.ads.sponsoredsearch.enumStrings
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.RegisterLiveness
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.cloneMutable
import app.morphe.util.findMutableMethodOf
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Where "Always use Clean mode" hooks, found by kept literals only (read from 577, 580 and 581,
 * 2026-10-07). The obfuscated names in these comments are for reviewers; the code never writes one
 * down.
 *
 * - Facebook's Clean mode (its "clear mode" inside) is switched by an event whose toString formats
 *   "%s: %s, enableClearMode: %s" (581 LX/88G). Every Clean mode toggle makes one: the three-dot
 *   menu item, the pinch gesture, the store below.
 * - The store is an injected singleton keeping one AtomicBoolean, the remembered choice for
 *   accounts Facebook gives sticky Clean mode (581 LX/87t). Its one setter sets the flag and posts
 *   the event, and only when the sticky config getter answers yes, so for everyone else the flag
 *   stays false.
 * - Three parts of the Reels viewer read the flag as they're made, each in its Litho
 *   createInitialState (577's overlay in a static helper it calls), each naming its spec in a log
 *   literal: FbShortsViewerOverlayComponentSpec (the buttons down the side; it skips the read for an
 *   ad reel), FbShortsViewerFooterComponentSpec and FbShortsVideoControlComponent. Up to 582 a
 *   fourth, UddPlayerControlComponent (582 LX/XIg;), read it too; 583 has no such component left,
 *   only its spec's CallerContext names. They read it with AtomicBoolean.get(); the video controls
 *   of 580 and 581 go through a static helper that boxes it (581 LX/18Q;->A0X). Each method reads it
 *   once. The flag's other readers (a toggle that leaves Clean mode, a lambda) are left alone.
 */

/** The toString format of Facebook's Clean mode event, the one literal that names it. */
internal const val CLEAR_MODE_EVENT = "%s: %s, enableClearMode: %s"

/** The spec names of the three viewer parts that start from the remembered Clean mode flag. */
internal val CLEAN_MODE_PARTS = listOf(
    "FbShortsViewerOverlayComponentSpec",
    "FbShortsViewerFooterComponentSpec",
    "FbShortsVideoControlComponent",
)

internal const val ATOMIC_BOOLEAN = "Ljava/util/concurrent/atomic/AtomicBoolean;"
private const val ATOMIC_BOOLEAN_GET = "$ATOMIC_BOOLEAN->get()Z"
private const val ATOMIC_BOOLEAN_SET = "$ATOMIC_BOOLEAN->set(Z)V"
private const val BOXED_BOOLEAN = "Ljava/lang/Boolean;"

private const val CLEAN_MODE = "$EXTENSION_PACKAGE/reels/ReelCleanMode;"
internal const val START_CLEAN = "$CLEAN_MODE->startClean(Z)Z"
internal const val START_CLEAN_BOXED = "$CLEAN_MODE->startClean($BOXED_BOOLEAN)$BOXED_BOOLEAN"

/** How far after the flag's field read the value may be taken. 581's boxed read is two after. */
private const val READ_WINDOW = 4

private const val PATCH = "Clean up Reels"

/**
 * One read of the flag: [moveResult] is the index of the move-result that keeps the answer in
 * [register], a boolean, or a Boolean when [boxed].
 */
internal data class CleanModeRead(val moveResult: Int, val register: Int, val boxed: Boolean)

private val Instruction.reference get() = (this as? ReferenceInstruction)?.reference

private fun Instruction.calls() = (reference as? MethodReference)?.toString()

private fun FieldReference.sameAs(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

/** The class whose toString loads [CLEAR_MODE_EVENT], among [holders], or null unless exactly one. */
internal fun clearModeEvent(holders: List<ClassDef>): String? = holders.filter { holder ->
    holder.methods.any { it.name == "toString" && it.parameterTypes.isEmpty() && holdsString(it, CLEAR_MODE_EVENT) }
}.map { it.type }.distinct().singleOrNull()

/**
 * Whether [field] is [store]'s remembered Clean mode flag: an AtomicBoolean of [store]'s own that a
 * method of [store] sets, right after reading it, in a method that also makes the [event].
 */
internal fun isCleanModeFlag(store: ClassDef, field: FieldReference, event: String): Boolean {
    if (field.definingClass != store.type || field.type != ATOMIC_BOOLEAN) return false
    return store.methods.any { setsFlag(it, event) { read -> read.sameAs(field) } }
}

/** Whether [method] makes the [event] and sets an AtomicBoolean [isFlag] accepts right after reading it. */
private fun setsFlag(method: Method, event: String, isFlag: (FieldReference) -> Boolean): Boolean {
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.any { it.opcode == Opcode.NEW_INSTANCE && (it.reference as TypeReference).type == event } &&
        code.zipWithNext().any { (read, next) ->
            read.opcode == Opcode.IGET_OBJECT && isFlag(read.reference as FieldReference) && next.calls() == ATOMIC_BOOLEAN_SET
        }
}

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

/**
 * The reads of the flag in [method]: each `iget-object` of a field [isFlag] accepts, whose value
 * (or a `move-object` copy of it) goes within [READ_WINDOW] instructions into `AtomicBoolean.get()`
 * or a static helper taking just the AtomicBoolean and answering a Boolean, kept by the next
 * instruction's move-result. A field read used any other way first isn't one.
 */
internal fun cleanModeReads(method: Method, isFlag: (FieldReference) -> Boolean): List<CleanModeRead> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        val read = code[at]
        if (read.opcode != Opcode.IGET_OBJECT || !isFlag(read.reference as FieldReference)) return@mapNotNull null
        val holders = mutableSetOf((read as OneRegisterInstruction).registerA)
        for (next in at + 1 until minOf(code.size - 1, at + 1 + READ_WINDOW)) {
            val instruction = code[next]
            if (instruction.opcode in MOVE_OBJECTS && (instruction as TwoRegisterInstruction).registerB in holders) {
                holders += instruction.registerA
                continue
            }
            val call = instruction.reference as? MethodReference ?: continue
            if (instruction.registers().none { it in holders }) continue
            val kept = code[next + 1]
            return@mapNotNull when {
                call.toString() == ATOMIC_BOOLEAN_GET && kept.opcode == Opcode.MOVE_RESULT ->
                    CleanModeRead(next + 1, (kept as OneRegisterInstruction).registerA, boxed = false)
                instruction.opcode in STATIC_CALLS && call.returnType == BOXED_BOOLEAN &&
                    call.parameterTypes.map { it.toString() } == listOf(ATOMIC_BOOLEAN) &&
                    kept.opcode == Opcode.MOVE_RESULT_OBJECT ->
                    CleanModeRead(next + 1, (kept as OneRegisterInstruction).registerA, boxed = true)
                else -> null
            }
        }
        null
    }
}

private val MOVE_OBJECTS = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/**
 * Every viewer part's read of the flag, with the class and method it's in, part by part. [holders]
 * answers the classes with a method loading a literal, and [classOf] a class by type. Refuses
 * unless the event, and exactly one read for each of [CLEAN_MODE_PARTS], are found.
 */
internal fun cleanModeHooks(
    holders: (String) -> List<ClassDef>,
    classOf: (String) -> ClassDef?,
): List<Triple<ClassDef, Method, CleanModeRead>> {
    val eventHolders = holders(CLEAR_MODE_EVENT)
    val event = clearModeEvent(eventHolders) ?: throw PatchException(
        "$PATCH: expected one class whose toString loads \"$CLEAR_MODE_EVENT\", found " +
            eventHolders.count { holder -> holder.methods.any { it.name == "toString" } },
    )
    val flags = mutableMapOf<String, Boolean>()
    val isFlag = { field: FieldReference ->
        flags.getOrPut(field.toString()) {
            field.type == ATOMIC_BOOLEAN && classOf(field.definingClass)?.let { isCleanModeFlag(it, field, event) } == true
        }
    }
    return CLEAN_MODE_PARTS.map { part ->
        val reads = holders(part).flatMap { owner ->
            methodsHolding(owner, part).flatMap { method -> cleanModeReads(method, isFlag).map { Triple(owner, method, it) } }
        }
        reads.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one read of Facebook's remembered Clean mode flag in a method naming \"$part\", found ${reads.size}",
        )
    }
}

/**
 * Each viewer part asks the extension right after it reads the remembered Clean mode flag, and goes
 * on with the answer in the same register: a yes stays yes, and the switch can turn a no into a yes.
 * Nothing else changes, so a part that skips the read (the overlay for an ad) stays Facebook's.
 */
internal fun BytecodePatchContext.startReelsInCleanMode() {
    val hooks = cleanModeHooks(
        holders = { literal ->
            classDefByStrings(literal, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        },
        classOf = { type -> classDefByOrNull(type) },
    )
    hooks.forEach { (owner, method, read) -> mutableClassDefBy(owner).findMutableMethodOf(method).startCleanAfter(read) }
}

/**
 * Puts the extension right after [read]'s move-result, on the same register, so nothing else is
 * borrowed. A jump to the instruction after the move-result keeps landing on it, past the hook,
 * since such a path didn't come from the read.
 */
internal fun MutableMethod.startCleanAfter(read: CleanModeRead) =
    if (read.boxed) askAfter(read.moveResult, read.register, START_CLEAN_BOXED, "move-result-object")
    else askAfter(read.moveResult, read.register, START_CLEAN, "move-result")

private fun MutableMethod.askAfter(moveResult: Int, register: Int, hook: String, keep: String) = addInstructions(
    moveResult + 1,
    """
        invoke-static/range { v$register .. v$register }, $hook
        $keep v$register
    """,
)

/*
 * The Clean mode button (#304), read from 585 arm64 and armeabi-v7a, 2026-10-30. Over a clean reel
 * the overlay shows a button whose tap Facebook logs as "tap_clear_mode" (585 LX/0b4J; case 11).
 *
 * - For an account with sticky Clean mode (585 LX/05YM;->A1M()Z, asked of the reel) the tap calls
 *   the store's setter with the opposite of the button's own state (585 LX/0cNr;->A0A, xor 1). The
 *   setter asks the same check first, then sets the flag and posts the Clean mode event on the bus
 *   the overlay listens to (585 LX/05ml;). For everyone else the tap posts the event on the
 *   viewer's other bus (LX/05YP;), which the overlay doesn't hear, so its value never changes and
 *   the button keeps its state: with the switch on it always offered to leave Clean mode.
 * - The overlay's listener (585 LX/09xH;) boxes the event's yes or no into a one-element state
 *   update, "updateState:FbShortsViewerOverlayComponent.onUpdateIsClearModeEnabled", and its state
 *   keeps it in an AtomicReference. An ad never reads the store and starts with a no it keeps, its
 *   listener ignoring the event.
 * - The button's builder (585 LX/0B6k;->A00, called from the overlay's render with that
 *   AtomicReference) returns nothing when the reference holds a no, and otherwise makes the button
 *   (585 LX/0cNr;) with its state read from the same reference again. So in Facebook's design the
 *   button only leaves Clean mode, and an ad gets none.
 *
 * Hooked: the sticky check in the setter and the same call nearest before the setter in the tap,
 * the listener's boxed state, and the builder's first read of the reference, which only that no
 * check uses.
 *
 * The Android 9 build (585 minapi28, read 2026-11-07) inlines the builder into the overlay's render
 * (LX/09xw;->A05, five AtomicReferences among its parameters). There the no check compares the value
 * with a Boolean through the Kotlin areEqual and keeps its answer in that Boolean's register, a no
 * skips past the button (LX/0grc;) to adding nothing to the column, and the same reference was read
 * once before for another part. So the hook takes the last read before the button is made.
 *
 * 586 (2026-11-09) builds the button in the overlay's own render (LX/090C;->A1E on arm64), reading
 * the references off the overlay's state, and reads the same one again after the no check for the
 * state it hands the button (LX/0dK1;). The hook takes the last read whose value goes straight into
 * the check.
 */

/** The point Facebook logs as the Clean mode button is tapped, in the tap's handler. */
internal const val CLEAR_MODE_TAP = "tap_clear_mode"

/** The overlay's state update as it hears the Clean mode event. */
internal const val OVERLAY_CLEAR_MODE_UPDATE =
    "updateState:FbShortsViewerOverlayComponent.onUpdateIsClearModeEnabled"

internal const val ATOMIC_REFERENCE = "Ljava/util/concurrent/atomic/AtomicReference;"
private const val ATOMIC_REFERENCE_GET = "$ATOMIC_REFERENCE->get()Ljava/lang/Object;"
private const val OBJECT = "Ljava/lang/Object;"
private const val OBJECT_ARRAY = "[Ljava/lang/Object;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"

internal const val STICKY_CLEAN = "$CLEAN_MODE->stickyClean(Z)Z"
internal const val MARK_LEFT_CLEAN = "$CLEAN_MODE->markLeftClean($OBJECT_ARRAY)$OBJECT_ARRAY"
internal const val CLEAN_BUTTON_STATE = "$CLEAN_MODE->cleanButtonState($OBJECT)$OBJECT"

/**
 * One hook of the Clean mode button: [call] right after the move-result at [moveResult] in [owner]'s
 * [method], on [register]. The builder's also names the button class it [made].
 */
internal data class ButtonHook(
    val owner: ClassDef,
    val method: Method,
    val moveResult: Int,
    val register: Int,
    val call: String,
    val made: String? = null,
)

private fun Method.code() = implementation?.instructions?.toList().orEmpty()

private fun Method.sameAs(call: MethodReference) = name == call.name && returnType == call.returnType &&
    parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** The register a move-result at [at] in [code] keeps, or null when [at] isn't one of [kinds]. */
private fun kept(code: List<Instruction>, at: Int, vararg kinds: Opcode) =
    code.getOrNull(at)?.takeIf { it.opcode in kinds }?.let { (it as OneRegisterInstruction).registerA }

/** The first register of each of [method]'s parameters, by index. They're the method's last registers, past `this`. */
private fun parameterRegisters(method: Method): List<Int> {
    val sizes = method.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    var next = method.implementation!!.registerCount - sizes.sum()
    return sizes.map { size -> next.also { next += size } }
}

/**
 * The four hooks of the Clean mode button. [holders] answers the classes with a method loading a
 * literal, and [classOf] a class by type. Refuses unless each is found exactly once.
 */
/** The Clean mode button's [tap] in [tapOwner], calling the [store]'s [setter] at [setterAt]. */
private class SetterSite(
    val event: String,
    val tapOwner: ClassDef,
    val tap: Method,
    val tapCode: List<Instruction>,
    val setterAt: Int,
    val store: ClassDef,
    val setter: Method,
)

private fun setterSite(holders: (String) -> List<ClassDef>, classOf: (String) -> ClassDef?): SetterSite {
    val event = clearModeEvent(holders(CLEAR_MODE_EVENT)) ?: refuse("expected one class whose toString loads \"$CLEAR_MODE_EVENT\"")

    val taps = holders(CLEAR_MODE_TAP).flatMap { owner -> methodsHolding(owner, CLEAR_MODE_TAP).map { owner to it } }
    val (tapOwner, tap) = taps.singleOrNull() ?: refuse("expected one method loading \"$CLEAR_MODE_TAP\", found ${taps.size}")
    val tapCode = tap.code()
    val literalAt = tapCode.indexOfFirst { (it.reference as? StringReference)?.string == CLEAR_MODE_TAP }

    // The store's setter: the call nearest before the literal to a method that makes the event and
    // sets an AtomicBoolean of its own class right after reading it.
    fun setterOf(call: MethodReference): Pair<ClassDef, Method>? {
        val store = classOf(call.definingClass) ?: return null
        val method = store.methods.firstOrNull { it.sameAs(call) } ?: return null
        return (store to method).takeIf { setsFlag(method, event) { it.definingClass == store.type && it.type == ATOMIC_BOOLEAN } }
    }
    val setterAt = (minOf(literalAt, tapCode.size) - 1 downTo 0).firstOrNull { at ->
        (tapCode[at].reference as? MethodReference)?.let(::setterOf) != null
    } ?: refuse("the method loading \"$CLEAR_MODE_TAP\" calls no Clean mode setter before it")
    val (store, setter) = setterOf(tapCode[setterAt].reference as MethodReference)!!
    return SetterSite(event, tapOwner, tap, tapCode, setterAt, store, setter)
}

internal fun cleanModeButtonHooks(
    holders: (String) -> List<ClassDef>,
    classOf: (String) -> ClassDef?,
): List<ButtonHook> {
    val site = setterSite(holders, classOf)
    val tapOwner = site.tapOwner
    val tap = site.tap
    val tapCode = site.tapCode
    val setterAt = site.setterAt
    val store = site.store
    val setter = site.setter
    val setterCall = tapCode[setterAt]

    // The sticky check: the setter's first no-argument yes-or-no call, asked before anything else.
    val setterCode = setter.code()
    val gateAt = setterCode.indexOfFirst {
        it.opcode in VIRTUAL_CALLS && (it.reference as MethodReference).let { call -> call.parameterTypes.isEmpty() && call.returnType == "Z" }
    }
    val setterGate = kept(setterCode, gateAt + 1, Opcode.MOVE_RESULT)?.takeIf { gateAt >= 0 }
        ?: refuse("the Clean mode setter ${store.type}->${setter.name} asks no yes-or-no check")
    val gate = setterCode[gateAt].calls()
    val tapGateAt = (setterAt - 1 downTo 0).firstOrNull { tapCode[it].calls() == gate }
        ?: refuse("the Clean mode button's tap doesn't ask $gate before the setter")
    val tapGate = kept(tapCode, tapGateAt + 1, Opcode.MOVE_RESULT)
        ?: refuse("the Clean mode button's tap doesn't keep $gate")

    // The button: the class whose boolean the tap flips for the setter.
    val wanted = setterCall.registers().last()
    val flip = (setterAt - 1 downTo maxOf(0, setterAt - READ_WINDOW)).map { tapCode[it] }.firstOrNull {
        it.opcode == Opcode.XOR_INT_LIT8 && (it as TwoRegisterInstruction).registerA == wanted
    } as TwoRegisterInstruction? ?: refuse("the Clean mode button's tap doesn't flip the button's state for the setter")
    val buttonState = tapCode.subList(0, setterAt).lastOrNull {
        it.opcode == Opcode.IGET_BOOLEAN && (it as TwoRegisterInstruction).registerA == flip.registerB
    } ?: refuse("the Clean mode button's tap doesn't read the button's state")
    val button = (buttonState.reference as FieldReference).definingClass

    // The overlay's listener: its one boxing of the event's value into the state update.
    val listeners = holders(OVERLAY_CLEAR_MODE_UPDATE).flatMap { owner ->
        methodsHolding(owner, OVERLAY_CLEAR_MODE_UPDATE).map { owner to it }
    }
    val (listenerOwner, listener) = listeners.singleOrNull()
        ?: refuse("expected one method loading \"$OVERLAY_CLEAR_MODE_UPDATE\", found ${listeners.size}")
    val listenerCode = listener.code()
    val boxings = listenerCode.indices.filter { at ->
        val call = listenerCode[at].reference as? MethodReference
        listenerCode[at].opcode in STATIC_CALLS && call != null && call.returnType == OBJECT_ARRAY &&
            call.parameterTypes.map { it.toString() } == listOf("Z") && kept(listenerCode, at + 1, Opcode.MOVE_RESULT_OBJECT) != null
    }
    val boxing = boxings.singleOrNull() ?: refuse("expected the overlay's listener to box the Clean mode value once, found ${boxings.size}")

    // The builder: called from the overlay with an AtomicReference, it makes the button. 586 makes the
    // button in the overlay's own render (`LX/090C;->A1E` on arm64), which reads the references off
    // the overlay's state.
    fun makesButton(method: Method) = method.code().any { it.opcode == Opcode.NEW_INSTANCE && (it.reference as TypeReference).type == button }
    val overlays = CLEAN_MODE_PARTS.first().let(holders)
    val builders = overlays.flatMap { overlay ->
        overlay.methods.flatMap { method ->
            method.code().mapNotNull { (it.reference as? MethodReference)?.takeIf { call -> ATOMIC_REFERENCE in call.parameterTypes.map(CharSequence::toString) } }
        }
    }.distinctBy { it.toString() }.mapNotNull { call ->
        val owner = classOf(call.definingClass) ?: return@mapNotNull null
        val method = owner.methods.firstOrNull { it.sameAs(call) } ?: return@mapNotNull null
        (owner to method).takeIf { makesButton(method) }
    }.ifEmpty { overlays.flatMap { overlay -> overlay.methods.filter(::makesButton).map { overlay to it } } }
    val (builderOwner, builder) = builders.singleOrNull()
        ?: refuse("expected one method the overlay calls with an AtomicReference, or one of its own, to make $button, found ${builders.size}")
    // Its last read of a reference before it makes the button that goes straight into a static
    // yes-or-no check of the value against a no, and nothing reads the value after that check. 585
    // keeps the check's answer in the value's own register. The Android 9 build inlines the builder
    // into the overlay's render, which reads the same reference once before for another part and
    // compares with a Boolean kept in another register, where its answer goes too. 586 reads it once
    // more between the check and the button, for the state the button is made with.
    val builderCode = builder.code()
    val buttonAt = builderCode.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && (it.reference as TypeReference).type == button }
    fun checkedRightAway(at: Int): Boolean {
        val value = kept(builderCode, at + 1, Opcode.MOVE_RESULT_OBJECT) ?: return false
        val check = builderCode.getOrNull(at + 2) ?: return false
        val checkCall = check.reference as? MethodReference ?: return false
        return check.opcode in STATIC_CALLS && checkCall.returnType == "Z" &&
            checkCall.parameterTypes.map { it.toString() } in VALUE_CHECKS &&
            check.registers().first() == value && kept(builderCode, at + 3, Opcode.MOVE_RESULT) != null
    }
    val reads = (buttonAt - 1 downTo 0).filter { builderCode[it].calls() == ATOMIC_REFERENCE_GET }
    if (reads.isEmpty()) refuse("the Clean mode button's builder reads no AtomicReference before it makes $button")
    val readAt = reads.firstOrNull(::checkedRightAway)
        ?: refuse("the Clean mode button's builder doesn't check its AtomicReference's value right away")
    val value = kept(builderCode, readAt + 1, Opcode.MOVE_RESULT_OBJECT)!!
    if (builder.readsAfter(readAt + 2, value).isNotEmpty()) refuse("the Clean mode button's builder reads its AtomicReference's value after the check")

    return listOf(
        ButtonHook(store, setter, gateAt + 1, setterGate, STICKY_CLEAN),
        ButtonHook(tapOwner, tap, tapGateAt + 1, tapGate, STICKY_CLEAN),
        ButtonHook(listenerOwner, listener, boxing + 1, kept(listenerCode, boxing + 1, Opcode.MOVE_RESULT_OBJECT)!!, MARK_LEFT_CLEAN),
        ButtonHook(builderOwner, builder, readAt + 1, value, CLEAN_BUTTON_STATE, made = button),
    )
}

/** The builder's check of the reference's value: against a boolean (585), or a Boolean (the Android 9 build). */
private val VALUE_CHECKS = listOf(listOf(OBJECT, "Z"), listOf(OBJECT, OBJECT))

private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/*
 * The sidebar's Clean mode button (#391), read from 585 arm64, 2026-11-05.
 *
 * - The store (585 LX/09hR;) is a public final class the injector makes through its one
 *   constructor, which takes nothing and ends in its one return-void.
 * - The setter (585 LX/09hR;->A01(LX/02xO;LX/0DhY;Z)V) returns at once without a player and for an
 *   account without sticky Clean mode, then logs. Its work comes next: it sets the flag, takes the
 *   bus from its lazy holder, posts the event made from the source and the value (21-29), and
 *   returns. The work reads nothing but the store, the source and the value.
 * - The tap hands the setter Facebook's own source, a static field read just before the call (585
 *   LX/02xO;->A1g).
 *
 * The patch keeps the store as it's made and copies the setter into the store as [STORE_SWITCH],
 * whose first instructions read the tap's source and jump to the work, so nothing checks a reel or
 * the account. The extension's switchStore stub calls the copy with no player.
 */

/** The copy of the store's setter, made public in the store. */
internal const val STORE_SWITCH = "hushfacebookSwitch"

internal const val KEEP_STORE = "$CLEAN_MODE->keepStore($OBJECT)V"

private const val SWITCH_STORE = "switchStore"

/** The [store]'s [setter], whose work from [workAt] sets the flag and posts the event from Facebook's [source]. */
internal class StoreSwitch(val store: ClassDef, val setter: Method, val source: FieldReference, val workAt: Int)

/** Where the sidebar's Clean mode button switches the store. Refuses unless all of it is as read from 585. */
internal fun cleanModeStoreSwitch(
    holders: (String) -> List<ClassDef>,
    classOf: (String) -> ClassDef?,
): StoreSwitch {
    val site = setterSite(holders, classOf)
    val store = site.store
    val setter = site.setter
    if (!AccessFlags.PUBLIC.isSet(store.accessFlags)) refuse("the Clean mode store ${store.type} isn't public")
    val init = store.methods.filter { it.name == "<init>" }
    if (init.singleOrNull()?.parameterTypes?.isEmpty() != true ||
        init.single().code().count { it.opcode == Opcode.RETURN_VOID } != 1
    ) refuse("the Clean mode store ${store.type} isn't made by one constructor taking nothing")
    val parameters = setter.parameterTypes.map(CharSequence::toString)
    if (AccessFlags.STATIC.isSet(setter.accessFlags) || parameters.size != 3 || parameters.last() != "Z" ||
        parameters.take(2).any { !it.startsWith("L") }
    ) refuse("the Clean mode setter ${store.type}->${setter.name} doesn't take a source, a player and a value")

    // The tap's source: the last write of the call's source register before the call.
    val sourceRegister = site.tapCode[site.setterAt].registers().getOrNull(1)
        ?: refuse("the Clean mode button's tap calls the setter with a range")
    val write = (site.setterAt - 1 downTo 0).map { site.tapCode[it] }.firstOrNull { it.written() == sourceRegister }
    val source = write?.takeIf { it.opcode == Opcode.SGET_OBJECT }?.reference as? FieldReference
    if (source == null || source.type != parameters.first()) {
        refuse("the Clean mode button's tap doesn't hand the setter a static ${parameters.first()}")
    }

    // The work: from the flag's read and set to the next return, in a line, reading only what it was given.
    val code = setter.code()
    val workAt = code.indices.firstOrNull { at ->
        code[at].opcode == Opcode.IGET_OBJECT &&
            (code[at].reference as FieldReference).let { it.definingClass == store.type && it.type == ATOMIC_BOOLEAN } &&
            code.getOrNull(at + 1)?.calls() == ATOMIC_BOOLEAN_SET
    } ?: refuse("the Clean mode setter doesn't set its flag")
    val end = (workAt until code.size).firstOrNull { code[it].opcode == Opcode.RETURN_VOID }
        ?: refuse("the Clean mode setter's work doesn't return")
    val (sourceParameter, _, valueParameter) = parameterRegisters(setter)
    val known = mutableSetOf(sourceParameter - 1, sourceParameter, valueParameter)
    for (at in workAt until end) {
        val instruction = code[at]
        if (instruction is OffsetInstruction || instruction.opcode == Opcode.THROW) {
            refuse("the Clean mode setter's work branches at $at")
        }
        instruction.reads().firstOrNull { it !in known }?.let { refuse("the Clean mode setter's work reads v$it at $at") }
        instruction.written()?.let { known += it }
    }
    if ((workAt until end).none { code[it].opcode == Opcode.NEW_INSTANCE && (code[it].reference as TypeReference).type == site.event }) {
        refuse("the Clean mode setter's work doesn't make the Clean mode event")
    }
    return StoreSwitch(store, setter, source, workAt)
}

/** The register [this] writes, or null. A check-cast writes the register it reads. */
private fun Instruction.written(): Int? =
    (this as? OneRegisterInstruction)?.registerA?.takeIf { opcode.setsRegister() }

/** The registers [this] reads. */
private fun Instruction.reads(): List<Int> {
    val all = when (this) {
        is FiveRegisterInstruction, is RegisterRangeInstruction -> registers()
        is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
        is TwoRegisterInstruction -> listOf(registerA, registerB)
        is OneRegisterInstruction -> listOf(registerA)
        else -> emptyList()
    }
    return if (written() != null && opcode != Opcode.CHECK_CAST) all.drop(1) else all
}

/**
 * The store keeps itself in the extension as it's made, its setter gets a copy that goes straight
 * to the work, and the extension's stub calls the copy with a null in the source's and the
 * player's places, the copy reading the source itself.
 */
private fun BytecodePatchContext.switchFromSidebar(switch: StoreSwitch) {
    val store = mutableClassDefBy(switch.store)
    store.methods.single { it.name == "<init>" }.let { init ->
        val end = init.implementation!!.instructions.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
        init.addInstructions(end, "invoke-static { p0 }, $KEEP_STORE")
    }
    val copy = switch.setter.cloneMutable(name = STORE_SWITCH, accessFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)
    copy.addInstructionsWithLabels(
        0,
        """
            sget-object p1, ${switch.source}
            goto :work
        """,
        ExternalLabel("work", copy.getInstruction(switch.workAt)),
    )
    store.methods.add(copy)
    val signature = "${switch.store.type}->$STORE_SWITCH(${switch.setter.parameterTypes.joinToString("")})V"
    val stub = mutableClassDefBy(CLEAN_MODE).methods.singleOrNull {
        it.name == SWITCH_STORE && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, OBJECT, "Z")
    } ?: refuse("the extension has no $SWITCH_STORE stub")
    // p1 only lends its register to the null.
    stub.addInstructions(
        0,
        """
            check-cast p0, ${switch.store.type}
            const/4 p1, 0x0
            invoke-virtual { p0, p1, p1, p2 }, $signature
            return-void
        """,
    )
}

/**
 * The Clean mode button switches both ways while "Always use Clean mode" is on: its tap and the
 * store take Facebook's sticky way, the overlay marks a reel the person took out, and the builder
 * keeps the button for that reel. Each hook hands a value to the extension and goes on with its
 * answer in the same register.
 */
internal fun BytecodePatchContext.toggleReelCleanMode() {
    val holders = { literal: String ->
        classDefByStrings(literal, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
    }
    val classOf = { type: String -> classDefByOrNull(type) }
    val hooks = cleanModeButtonHooks(holders, classOf)
    // #391: the sidebar's Clean mode button goes through the store. Read before any edit, and a
    // build that moved it only loses that button, so it warns rather than refusing the patch.
    val storeSwitch = try {
        cleanModeStoreSwitch(holders, classOf)
    } catch (refused: PatchException) {
        patchLog.warning("${refused.message} Reels get no Clean mode button on their sidebar.")
        null
    }
    storeSwitch?.let { switchFromSidebar(it) }
    // #329: the builder's row for a kept button goes over the details. Its block sits further down
    // the builder than the value's read, so it goes in first.
    val builder = hooks.last()
    val float = cleanButtonFloat(builder, holders, classOf) { type ->
        buildList {
            classDefForEachReferencing(types = listOf(type)) { classDef ->
                if (!classDef.type.startsWith(EXTENSION_CLASSES)) addAll(classDef.methods.filter { isStaticMethod(it) })
            }
        }
    }
    mutableClassDefBy(builder.owner).findMutableMethodOf(builder.method).floatKeptButton(float)
    hooks.sortedByDescending { it.moveResult }.forEach { hook ->
        val keep = if (hook.call.endsWith(")Z")) "move-result" else "move-result-object"
        mutableClassDefBy(hook.owner).findMutableMethodOf(hook.method).askAfter(hook.moveResult, hook.register, hook.call, keep)
    }
}

/*
 * The kept button's room (#329), read from 585 arm64, 2026-11-05. The overlay's render lays its
 * parts out in one column (585 LX/09GM;->A17): the reel's details, which grow to fill it, then the
 * row the builder makes for the Clean mode button. Out of Clean mode Facebook makes no row, so the
 * details reach the bottom. The button kept for a reel taken out took its row's height from the
 * details (174 px on the 1080 px wide test screen), which all moved up by that much and left a gap
 * above the seek bar.
 *
 * - The builder makes the row's style with Litho's margin helper (585 LX/02dj;->A0D: the style,
 *   then nine dimensions for all, horizontal, vertical, start, top, end, bottom, left and right)
 *   and hands it first to the row's constructor (585 LX/02jJ;), the object it returns. The row
 *   also gets a list made from the button alone, which is how the patch finds it: the Android 9
 *   build's inlined builder makes other rows the same way (LX/02eS;->A0D, LX/02k2;) and adds this
 *   one to the column rather than returning it.
 * - Litho's Kotlin helpers set a position type (585 LX/02cP;->A08, the style and Yoga's position
 *   type enum, LX/0370;, which keeps the names "STATIC", "RELATIVE" and "ABSOLUTE") and position
 *   insets (585 LX/02cP;->A03, the style and nine dimensions, each read with one constant of an
 *   edge enum, LX/0371;, which keeps names like "POSITION_END" and "POSITION_BOTTOM").
 *
 * Hooked: right after the margin helper answers, a kept button's style is also positioned
 * absolutely, 0 from the end and 0 from the bottom. Its margins still apply, so the button sits
 * where it sits over a clean reel, and the details keep the whole column.
 */

internal const val FLOAT_CLEAN_BUTTON = "$CLEAN_MODE->floatCleanButton()Z"

/** Yoga's position types, the names its enum keeps. */
internal val POSITION_TYPES = listOf("STATIC", "RELATIVE", "ABSOLUTE")
private const val ABSOLUTE = "ABSOLUTE"

/** Two of the edges Litho's position helper sets, the names its edge enum keeps. */
internal const val POSITION_END = "POSITION_END"
internal const val POSITION_BOTTOM = "POSITION_BOTTOM"

/** How many dimensions Litho's margin and position helpers take after the style. */
internal const val EDGES = 9

private const val ENUM = "Ljava/lang/Enum;"

/**
 * Where the kept button's row is laid over the details: in the builder at [at], right after the
 * margin helper's move-result keeps the row's style in [style]. Nothing reads the ten registers from
 * [range] there before writing them again, so the block borrows them. [positionType] sets
 * [absolute] on a style, and [position] sets its insets, the end one at [end] and the bottom one at
 * [bottom] among its [EDGES] dimensions, each a [dimension] made from a long.
 */
internal data class FloatHook(
    val at: Int,
    val style: Int,
    val range: Int,
    val absolute: String,
    val positionType: String,
    val position: String,
    val dimension: String,
    val end: Int,
    val bottom: Int,
)

internal fun isStaticMethod(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags)

private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** Whether [params] and [returns] are a Litho style helper's: the style, then [EDGES] of [dimension], answering the style. */
private fun isStyleHelper(params: List<CharSequence>, returns: String, style: String, dimension: String?) =
    returns == style && params.size == EDGES + 1 && params[0].toString() == style &&
        dimension != null && params.drop(1).all { it.toString() == dimension }

/**
 * Every register [this] names. A call lists both halves of a wide argument; any other instruction
 * whose mnemonic works on a wide value, a long or a double is taken to use the register after each
 * one it names as well.
 */
private fun Instruction.allRegisters(): List<Int> {
    val named = when (this) {
        is FiveRegisterInstruction, is RegisterRangeInstruction -> return registers()
        is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
        is TwoRegisterInstruction -> listOf(registerA, registerB)
        is OneRegisterInstruction -> listOf(registerA)
        else -> return emptyList()
    }
    val mnemonic = opcode.name // opcode-name-ok: the smali mnemonic is what's matched, "iget-wide", "add-long"
    val wide = listOf("wide", "long", "double").any { it in mnemonic }
    return if (wide) named.flatMap { listOf(it, it + 1) } else named
}

private val DIRECT_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * The block that lays a kept button's row over the details, in [builder]. [holders] answers the
 * classes with a method loading a literal, [classOf] a class by type, and [staticsNaming] the static
 * methods of the classes that name a type. Refuses unless each part is found exactly once.
 */
internal fun cleanButtonFloat(
    hook: ButtonHook,
    holders: (String) -> List<ClassDef>,
    classOf: (String) -> ClassDef?,
    staticsNaming: (String) -> List<Method>,
): FloatHook {
    val builder = hook.method
    val button = hook.made ?: refuse("the Clean mode button's builder hook names no button")
    val code = builder.code()
    // The button goes alone into a list, made by a static call, while its register still holds it.
    val buttonAt = code.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && (it.reference as TypeReference).type == button }
    val made = (code.getOrNull(buttonAt) as? OneRegisterInstruction)?.registerA
        ?: refuse("the Clean mode button's builder doesn't make $button")
    val listAt = (buttonAt + 1 until code.size).firstOrNull { at ->
        val call = code[at].reference as? MethodReference
        code[at].opcode in STATIC_CALLS && call?.returnType == ARRAY_LIST &&
            call.parameterTypes.map(CharSequence::toString) == listOf(OBJECT) && code[at].registers() == listOf(made)
    } ?: refuse("the Clean mode button's builder doesn't put $button in a list")
    if ((buttonAt + 1 until listAt).any { code[it].opcode.setsRegister() && (code[it] as? OneRegisterInstruction)?.registerA == made }) {
        refuse("the Clean mode button's builder puts something else in $button's register before its list")
    }
    val list = kept(code, listAt + 1, Opcode.MOVE_RESULT_OBJECT) ?: refuse("the Clean mode button's builder doesn't keep its list")
    // The row: the next object it makes, constructed with its style first and handed that list. 585's
    // builder returns it. The Android 9 build's, inlined into the overlay's render, adds it to the column.
    val rowAt = (listAt + 2 until code.size).firstOrNull { code[it].opcode == Opcode.NEW_INSTANCE }
        ?: refuse("the Clean mode button's builder makes no row after its list")
    val rowType = (code[rowAt].reference as TypeReference).type
    // Its constructor takes the row's register or a copy of it: the Android 9 build moves the row into a range.
    val row = mutableSetOf((code[rowAt] as OneRegisterInstruction).registerA)
    var constructorAt = -1
    for (at in rowAt + 1 until code.size) {
        val instruction = code[at]
        if (instruction.opcode in DIRECT_CALLS && instruction.calls()?.startsWith("$rowType-><init>(") == true) {
            if (instruction.registers().firstOrNull() in row) constructorAt = at
            break
        }
        if (instruction.opcode in OBJECT_MOVES && (instruction as TwoRegisterInstruction).registerB in row) {
            row += instruction.registerA
        } else if (instruction.opcode.setsRegister()) {
            (instruction as? OneRegisterInstruction)?.let { row -= it.registerA }
        }
    }
    if (constructorAt < 0) refuse("the Clean mode button's builder doesn't construct its row")
    if (list !in code[constructorAt].registers().drop(2)) refuse("the button's row $rowType isn't handed the button's list")
    val constructor = code[constructorAt].reference as MethodReference
    val styleType = constructor.parameterTypes.firstOrNull()?.toString() ?: refuse("the button's row takes no style")
    val style = code[constructorAt].registers()[1]

    // The style comes straight from the margin helper, and nothing touches it before the row has it.
    val keptAt = (constructorAt - 1 downTo 1).firstOrNull { at ->
        code[at].opcode == Opcode.MOVE_RESULT_OBJECT && (code[at] as OneRegisterInstruction).registerA == style
    } ?: refuse("the button row's style isn't an answer the builder keeps")
    if ((keptAt + 1 until constructorAt).any { style in code[it].allRegisters() }) refuse("the button row's style changes before the row has it")
    val margins = code[keptAt - 1]
    val marginCall = margins.reference as? MethodReference
    val dimension = marginCall?.parameterTypes?.getOrNull(1)?.toString()
    if (margins.opcode != Opcode.INVOKE_STATIC_RANGE || marginCall == null ||
        !isStyleHelper(marginCall.parameterTypes, marginCall.returnType, styleType, dimension)
    ) refuse("the button row's style doesn't come from Litho's margin helper")
    if (style > 255) refuse("the button row's style sits past v255")
    // The block borrows ten registers in a row that nothing reads before writing them again: the
    // margin helper's when they're free, as on 585, otherwise the lowest free run. The Android 9
    // build's inlined builder hands one of the margin helper's registers on to the row.
    val live = RegisterLiveness.of(builder).liveInto(keptAt + 1)
    val last = minOf(builder.implementation!!.registerCount, 256) - 1
    fun free(start: Int) = start >= 0 && start + EDGES <= last && (start..start + EDGES).none { it in live }
    val range = (margins as RegisterRangeInstruction).startRegister.takeIf(::free)
        ?: (0..last - EDGES).firstOrNull(::free)
        ?: refuse("the Clean mode button's builder has no ten registers free where its row gets its style")

    // The edge enum, and the position helper reading one of its edges for each dimension it's handed.
    val edges = holders(POSITION_BOTTOM).filter { enumStrings(it).containsAll(listOf(POSITION_END, POSITION_BOTTOM)) }
    val positions = edges.flatMap { edge ->
        staticsNaming(edge.type).filter { helper ->
            isStaticMethod(helper) && isStyleHelper(helper.parameterTypes, helper.returnType, styleType, dimension) &&
                helper.code().any { it.opcode == Opcode.SGET_OBJECT && (it.reference as FieldReference).definingClass == edge.type }
        }.map { edge to it }
    }.distinctBy { it.second.signature() }
    val (edge, position) = positions.singleOrNull()
        ?: refuse("expected one Litho helper setting position insets, found ${positions.map { it.second.signature() }}")

    // Yoga's position type enum, and the helper beside the position helper that sets one on a style.
    // Other Kotlin style builders take a position type too, each in its own class.
    val types = holders(ABSOLUTE).filter { it.superclass == ENUM && enumStrings(it).containsAll(POSITION_TYPES) }
    val setters = types.flatMap { type ->
        staticsNaming(type.type).filter {
            isStaticMethod(it) && it.definingClass == position.definingClass && it.returnType == styleType &&
                it.parameterTypes.map(CharSequence::toString) == listOf(styleType, type.type)
        }
    }.distinctBy { it.signature() }
    val setter = setters.singleOrNull()
        ?: refuse("expected one Litho helper beside ${position.signature()} setting a position type, found ${setters.map { it.signature() }}")
    val positionType = types.first { it.type == setter.parameterTypes[1].toString() }
    val absolute = enumConstantFields(positionType).entries.singleOrNull { it.value == ABSOLUTE }?.key
        ?: refuse("Yoga's position type enum ${positionType.type} keeps no $ABSOLUTE")
    val names = enumConstantFields(edge)
    val parameters = parameterRegisters(position)
    val positionCode = position.code()
    val slots = mutableMapOf<String, Int>()
    positionCode.forEachIndexed { at, instruction ->
        val field = instruction.reference as? FieldReference
        if (instruction.opcode != Opcode.SGET_OBJECT || field?.definingClass != edge.type) return@forEachIndexed
        // The dimension it goes with is the one whose value was read just before.
        val read = (at - 1 downTo 0).map { positionCode[it] }.firstOrNull { it.opcode == Opcode.IGET_WIDE } as TwoRegisterInstruction?
        val slot = read?.let { parameters.indexOf(it.registerB) }?.takeIf { it in 1..EDGES } ?: return@forEachIndexed
        names[field.name]?.let { slots[it] = slot }
    }
    val end = slots[POSITION_END] ?: refuse("Litho's position helper ${position.signature()} sets no $POSITION_END")
    val bottom = slots[POSITION_BOTTOM] ?: refuse("Litho's position helper ${position.signature()} sets no $POSITION_BOTTOM")
    if (end == bottom) refuse("Litho's position helper reads $POSITION_END and $POSITION_BOTTOM from one dimension")

    // A dimension is made from a long, as the builder makes its own.
    val fromLong = classOf(dimension!!)?.methods?.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("J") }
    if (fromLong != true) refuse("Litho's dimension $dimension isn't made from a long")

    return FloatHook(
        at = keptAt + 1,
        style = style,
        range = range,
        absolute = "${positionType.type}->$absolute:${positionType.type}",
        positionType = setter.signature(),
        position = position.signature(),
        dimension = dimension,
        end = end,
        bottom = bottom,
    )
}

/**
 * The block itself: asks the extension, and for a kept button sets the absolute position type, then
 * a 0 inset from the end and from the bottom, in the borrowed registers, before the row gets its
 * style. Otherwise it jumps over all of it, to Facebook's next instruction.
 */
internal fun floatSmali(hook: FloatHook): String {
    fun v(slot: Int) = "v${hook.range + slot}"
    return buildString {
        appendLine("invoke-static { }, $FLOAT_CLEAN_BUTTON")
        appendLine("move-result ${v(0)}")
        appendLine("if-eqz ${v(0)}, :facebook")
        appendLine("move-object/from16 ${v(0)}, v${hook.style}")
        appendLine("sget-object ${v(1)}, ${hook.absolute}")
        appendLine("invoke-static/range { ${v(0)} .. ${v(1)} }, ${hook.positionType}")
        appendLine("move-result-object ${v(0)}")
        appendLine("new-instance ${v(1)}, ${hook.dimension}")
        appendLine("const-wide/16 ${v(2)}, 0x0")
        appendLine("invoke-direct/range { ${v(1)} .. ${v(3)} }, ${hook.dimension}-><init>(J)V")
        listOf(hook.end, hook.bottom).filter { it != 1 }.forEach { appendLine("move-object/16 ${v(it)}, ${v(1)}") }
        (1..EDGES).filter { it != hook.end && it != hook.bottom }.forEach { appendLine("const/16 ${v(it)}, 0x0") }
        appendLine("invoke-static/range { ${v(0)} .. ${v(EDGES)} }, ${hook.position}")
        appendLine("move-result-object v${hook.style}")
    }
}

internal fun MutableMethod.floatKeptButton(hook: FloatHook) =
    addInstructionsWithLabels(hook.at, floatSmali(hook), ExternalLabel("facebook", getInstruction(hook.at)))
