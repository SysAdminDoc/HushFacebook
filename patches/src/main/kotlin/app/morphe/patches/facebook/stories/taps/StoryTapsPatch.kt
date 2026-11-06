/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.taps

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.requireParameterIntact
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.extension.requireThisIntact
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val STORY_TAPS_PATCH = "Story viewer taps"

/** The string the story viewer's tap controller writes into its trace as a single tap starts. */
internal const val TAP_TRACE = "StoryViewerTapController.onSingleTap"

internal const val STORY_TAPS = "$EXTENSION_PACKAGE/stories/StoryTaps;"
internal const val ON_TAP = "$STORY_TAPS->onTap(Ljava/lang/Object;Landroid/view/MotionEvent;)Z"
internal const val SECOND_TAP = "$STORY_TAPS->secondTap(Landroid/view/MotionEvent;)Z"

/** Facebook's own names for a tap's two navigations, kept in its navigation enum. */
internal const val TAP_BACKWARD = "TAP_BACKWARD"
internal const val TAP_FORWARD = "TAP_FORWARD"

/** The pause reasons, kept in Facebook's reason enum: the finger on the story, and its play and pause toggle. */
internal const val DRAGGING = "DRAGGING_IN_GESTURE_LISTENER"
internal const val PAUSE_BUTTON = "PAUSE_STORY_VIEWER_ON_TAP_OF_PAUSE_BUTTON"

/** The stubs the patch fills in. */
internal const val PAUSER_OF = "pauserOf"
internal const val PAUSE_WITH = "pauseWith"
internal const val RESUME_WITH = "resumeWith"
internal const val PAUSE_REASON = "pauseReason"

/** The callback the patch gives the gesture listener. */
internal const val DOUBLE_TAP_EVENT = "onDoubleTapEvent"

private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val VIEW = "Landroid/view/View;"
private const val SIMPLE_GESTURE_LISTENER = "Landroid/view/GestureDetector\$SimpleOnGestureListener;"

/**
 * A play and pause button for stories that shows on a tap in the middle, like Reels, and stories
 * that change only on a double tap (#380).
 *
 * The story viewer's tap controller writes [TAP_TRACE] into its trace and then picks a navigation
 * from where the tap landed: back on one side, forward on the other, each one interface call on its
 * navigator with a constant of Facebook's navigation enum. The extension is asked in front of both
 * and can hold the tap. The viewer's gesture listener takes a quick second tap as a double tap and
 * drops it, so the patch gives the listener a double tap callback that hands the second tap's lift
 * to its single tap code. The pause is Facebook's own: the same controller resumes from the finger
 * on the story through an interface on a field, and that interface's other method pauses. The
 * extension uses it with the reason Facebook's play and pause toggle pauses with.
 *
 * In the default selection with both switches off, so nothing changes until one is turned on.
 */
@Suppress("unused")
val storyTapsPatch = bytecodePatch(
    name = "Story viewer taps",
    description = "A tap in the middle of a story shows a play and pause button, like Reels, and a second switch " +
        "makes stories change only on a double tap, so a single tap pauses or plays instead. Both start off. Turn them on in " +
        "Hushfacebook settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireStatusMethod("storyTaps")
        val tap = storyTapMethod()
        val route = storyTapRoute(tap)
        val listener = tapListener(tap)
        mutableClassDefBy(tap.definingClass).methods.single { it.name == tap.name && it.parameterTypes == tap.parameterTypes }
            .holdTaps(route.navigations)
        mutableClassDefBy(listener.type).methods.add(secondTapCallback(listener.type))
        fillStoryTapStubs(route)
        enableStatus("storyTaps")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$STORY_TAPS_PATCH: $detail")

/** The story viewer's single tap: an instance method on (MotionEvent, View) answering a boolean, holding [TAP_TRACE]. */
internal fun isStoryTap(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT, VIEW) && holdsString(method, TAP_TRACE)

internal fun BytecodePatchContext.storyTapMethod(): Method {
    val found = mutableListOf<Method>()
    classDefForEachReferencing(strings = listOf(TAP_TRACE)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        classDef.methods.filterTo(found, ::isStoryTap)
    }
    return found.singleOrNull() ?: refuse("expected one story tap holding \"$TAP_TRACE\", found ${found.size}")
}

/** An interface call at [index] on one enum constant, which the instruction before loads. */
internal class EnumCall(val index: Int, val call: MethodReference, val constant: String, val enum: String)

/** Every interface call in [method] that takes one enum constant loaded right before it. */
internal fun enumCalls(method: Method): List<EnumCall> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return (1 until code.size).mapNotNull { index ->
        val call = code[index] as? FiveRegisterInstruction ?: return@mapNotNull null
        if (code[index].opcode != Opcode.INVOKE_INTERFACE || call.registerCount != 2) return@mapNotNull null
        val target = (call as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        val type = target.parameterTypes.singleOrNull()?.toString() ?: return@mapNotNull null
        val load = code[index - 1]
        if (load.opcode != Opcode.SGET_OBJECT || target.returnType != "V") return@mapNotNull null
        val field = (load as ReferenceInstruction).reference as FieldReference
        if ((load as OneRegisterInstruction).registerA != call.registerD || field.type != type) return@mapNotNull null
        EnumCall(index, target, field.toString(), type)
    }
}

/** What the hooks and stubs need from the tap controller. */
internal class StoryTapRoute(
    /** Where the back and forward navigations are called, in order. */
    val navigations: List<Int>,
    /** The controller's field holding its pauser. */
    val pauserField: String,
    /** The pauser's pause and resume, each taking a reason. */
    val pause: String,
    val resume: String,
    /** The reason Facebook's play and pause toggle pauses with. */
    val reason: String,
)

/**
 * The navigations, pauser and pause reason of [tap], found by Facebook's own enum constant names.
 * Throws naming what's missing, or what the extension couldn't reach.
 */
internal fun BytecodePatchContext.storyTapRoute(tap: Method): StoryTapRoute {
    val calls = enumCalls(tap)
    val enums = calls.map { it.enum }.distinct().associateWith { classDefByOrNull(it) }
    val navigation = enums.filter { (_, def) -> def != null && enumConstant(def, TAP_FORWARD) != null }.keys.singleOrNull()
        ?: refuse("expected one navigation enum with $TAP_FORWARD among ${tap.definingClass}->${tap.name}'s calls")
    val navigationDef = enums.getValue(navigation)!!
    val navigations = calls.filter { it.enum == navigation }
    val wanted = setOf(enumConstant(navigationDef, TAP_BACKWARD), enumConstant(navigationDef, TAP_FORWARD))
    if (navigations.size != 2 || navigations.map { it.constant }.toSet() != wanted) {
        refuse("expected one $TAP_BACKWARD and one $TAP_FORWARD navigation, found ${navigations.map { it.constant }}")
    }
    val resume = calls.filter { call -> enums[call.enum]?.let { enumConstant(it, DRAGGING) } == call.constant }
        .singleOrNull() ?: refuse("expected one resume from $DRAGGING in ${tap.definingClass}->${tap.name}")
    val reasonDef = enums.getValue(resume.enum)!!
    val reason = enumConstant(reasonDef, PAUSE_BUTTON) ?: refuse("${reasonDef.type} has no $PAUSE_BUTTON")
    val pauserType = resume.call.definingClass
    val pauserDef = classDefByOrNull(pauserType) ?: refuse("the pauser $pauserType isn't in the app")
    val pauses = pauserDef.methods.filter {
        it.name != resume.call.name && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(reasonDef.type)
    }
    val pause = pauses.singleOrNull() ?: refuse("expected one pause beside ${resume.call.name} on $pauserType, found ${pauses.size}")
    val fields = tap.implementation!!.instructions.filter { it.opcode == Opcode.IGET_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }.filter { it.type == pauserType }
        .distinctBy(FieldReference::toString)
    val field = fields.singleOrNull() ?: refuse("expected one $pauserType field read by the tap, found ${fields.size}")
    // pauserOf is handed the tap's own `this`, so the field has to sit on the tap's class, one it extends,
    // or the subclass the tap checks `this` against (in 585 the tap is on an abstract class and checks for its one subclass).
    var owner: String? = tap.definingClass
    while (owner != null && owner != field.definingClass) owner = classDefByOrNull(owner)?.superclass
    if (owner == null && !checksThisIs(tap, field.definingClass)) {
        refuse("${field.definingClass}'s pauser field isn't on ${tap.definingClass}, a class it extends or one it checks `this` against")
    }

    // The stubs live in the extension's own package, so all of these have to be public.
    val controllerDef = classDefByOrNull(field.definingClass) ?: refuse("the controller ${field.definingClass} isn't in the app")
    val pauserField = controllerDef.fields.singleOrNull { it.name == field.name && it.type == field.type }
    if (!AccessFlags.PUBLIC.isSet(controllerDef.accessFlags) || pauserField == null || !AccessFlags.PUBLIC.isSet(pauserField.accessFlags)) {
        refuse("${field.definingClass} or its pauser field isn't public")
    }
    if (!AccessFlags.PUBLIC.isSet(pauserDef.accessFlags) || !AccessFlags.INTERFACE.isSet(pauserDef.accessFlags)) {
        refuse("the pauser $pauserType isn't a public interface")
    }
    val reasonField = reasonDef.fields.single { "${reasonDef.type}->${it.name}:${it.type}" == reason }
    if (!AccessFlags.PUBLIC.isSet(reasonDef.accessFlags) || !AccessFlags.PUBLIC.isSet(reasonField.accessFlags)) {
        refuse("the reason enum ${reasonDef.type} or its $PAUSE_BUTTON isn't public")
    }
    return StoryTapRoute(
        navigations = navigations.map { it.index }.sorted(),
        pauserField = field.toString(),
        pause = "$pauserType->${pause.name}(${reasonDef.type})V",
        resume = "$pauserType->${resume.call.name}(${reasonDef.type})V",
        reason = reason,
    )
}

/**
 * Whether [method] asks `instance-of` [type] of its own `this`, or of a copy of it a plain
 * `move-object` made and nothing wrote over since (585's Android 9 build copies `this` from v17 to v1
 * first, `LX/0DCd;->Ia7`).
 */
internal fun checksThisIs(method: Method, type: String): Boolean {
    val code = method.implementation ?: return false
    val thisRegister = code.registerCount - 1 - method.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }.sum()
    val copies = mutableSetOf(thisRegister)
    val moves = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
    for (instruction in code.instructions) {
        if (instruction.opcode == Opcode.INSTANCE_OF && (instruction as TwoRegisterInstruction).registerB in copies &&
            (instruction as ReferenceInstruction).reference.toString() == type
        ) {
            return true
        }
        val written = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        if (instruction.opcode in moves && (instruction as TwoRegisterInstruction).registerB in copies) {
            copies += written
        } else if (instruction.opcode.setsRegister() && written != thisRegister) {
            copies -= written
            if (instruction.opcode.setsWideRegister()) copies -= written + 1
        }
    }
    return false
}

/** Whether [method] is a gesture listener's single tap lift that hands the tap to a method named [tapName] on (MotionEvent, View). */
internal fun handsTapTo(method: Method, tapName: String): Boolean =
    method.name == "onSingleTapUp" && method.returnType == "Z" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT) &&
        method.implementation?.instructions?.any {
            // Only an invoke refers to a method.
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            ref?.name == tapName && ref.returnType == "Z" &&
                ref.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT, VIEW)
        } == true

/**
 * The viewer's gesture listener: the one class whose single tap lift hands the tap on to [tap]'s
 * name. It has to extend Android's simple gesture listener, which the detector also asks about
 * double taps, and nothing between them may answer [DOUBLE_TAP_EVENT] already.
 */
internal fun BytecodePatchContext.tapListener(tap: Method): ClassDef {
    val found = mutableListOf<ClassDef>()
    classDefForEachReferencing(names = listOf(tap.name)) { classDef ->
        if (classDef.type.startsWith(EXTENSION_CLASSES)) return@classDefForEachReferencing
        if (classDef.methods.any { handsTapTo(it, tap.name) }) found += classDef
    }
    val listener = found.singleOrNull() ?: refuse("expected one gesture listener handing taps to ${tap.name}, found ${found.size}")
    var type: String? = listener.type
    while (type != null && type != SIMPLE_GESTURE_LISTENER) {
        val def = classDefByOrNull(type) ?: refuse("${listener.type} doesn't extend $SIMPLE_GESTURE_LISTENER")
        if (def.methods.any { it.name == DOUBLE_TAP_EVENT && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT) }) {
            refuse("${def.type} already answers $DOUBLE_TAP_EVENT")
        }
        type = def.superclass
    }
    if (type == null) refuse("${listener.type} doesn't extend $SIMPLE_GESTURE_LISTENER")
    return listener
}

/**
 * Asks the extension in front of each navigation at [navigations] and skips the navigation when it
 * answers true. The call reads `this` and the MotionEvent from their own registers, so both have to
 * be intact there, and borrows one local nothing reads from the navigation on.
 */
internal fun MutableMethod.holdTaps(navigations: List<Int>) {
    requireThisIntact(STORY_TAPS_PATCH, navigations)
    requireParameterIntact(STORY_TAPS_PATCH, 0, navigations)
    val registers = navigations.associateWith { freeLocalsAt(STORY_TAPS_PATCH, it, 1, targets = listOf(it + 1), highest = 255).single() }
    for (index in navigations.sortedDescending()) {
        val held = registers.getValue(index)
        addInstructionsWithLabels(
            index,
            """
                invoke-static/range { p0 .. p1 }, $ON_TAP
                move-result v$held
                if-nez v$held, :held
            """,
            ExternalLabel("held", getInstruction(index + 1)),
        )
    }
}

/**
 * [DOUBLE_TAP_EVENT] for [listener]: when the extension takes the event as a double tap's second
 * lift, it goes to the listener's own single tap lift. Otherwise it answers false, as Android's
 * simple listener does.
 */
internal fun secondTapCallback(listener: String): MutableMethod = ImmutableMethod(
    listener,
    DOUBLE_TAP_EVENT,
    listOf(ImmutableMethodParameter(MOTION_EVENT, null, null)),
    "Z",
    AccessFlags.PUBLIC.value,
    null,
    null,
    MutableMethodImplementation(3),
).toMutable().apply {
    addInstructions(
        0,
        """
            invoke-static { p1 }, $SECOND_TAP
            move-result v0
            if-eqz v0, :stock
            invoke-virtual { p0, p1 }, $listener->onSingleTapUp($MOTION_EVENT)Z
            move-result v0
            return v0
            :stock
            const/4 v0, 0x0
            return v0
        """,
    )
}

/** Fills the extension's stubs with the pauser field, its pause and resume and the pause reason of [route]. */
internal fun BytecodePatchContext.fillStoryTapStubs(route: StoryTapRoute) {
    val extension = mutableClassDefBy(STORY_TAPS)
    fun stub(name: String) = extension.methods.singleOrNull { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("$STORY_TAPS has no static $name")
    val controller = route.pauserField.substringBefore("->")
    val pauser = route.pause.substringBefore("->")
    val reasonType = route.reason.substringAfterLast(":")
    // A controller of another class answers null, which leaves the taps to Facebook, rather than throwing.
    // D8 builds the stub in the one register its parameter needs, so it's rebuilt with a second to test in.
    val stubbed = stub(PAUSER_OF)
    val pauserOf = ImmutableMethod(
        STORY_TAPS, stubbed.name, stubbed.parameters, stubbed.returnType, stubbed.accessFlags, null, null,
        MutableMethodImplementation(2),
    ).toMutable()
    if (!extension.methods.remove(stubbed)) refuse("$STORY_TAPS->$PAUSER_OF couldn't be replaced")
    extension.methods.add(pauserOf)
    pauserOf.addInstructionsWithLabels(
        0,
        """
            instance-of v0, p0, $controller
            if-eqz v0, :other
            check-cast p0, $controller
            iget-object p0, p0, ${route.pauserField}
            return-object p0
            :other
            const/4 p0, 0x0
            return-object p0
        """,
    )
    for ((name, call) in listOf(PAUSE_WITH to route.pause, RESUME_WITH to route.resume)) {
        stub(name).addInstructions(
            0,
            """
                check-cast p0, $pauser
                check-cast p1, $reasonType
                invoke-interface { p0, p1 }, $call
                return-void
            """,
        )
    }
    val reason = stub(PAUSE_REASON)
    if (reason.implementation!!.registerCount < 1) refuse("$STORY_TAPS->$PAUSE_REASON has no register to load the reason into")
    reason.addInstructions(
        0,
        """
            sget-object v0, ${route.reason}
            return-object v0
        """,
    )
}
