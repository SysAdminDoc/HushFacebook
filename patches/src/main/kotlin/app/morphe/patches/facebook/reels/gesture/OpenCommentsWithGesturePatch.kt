/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.options.UFI_DOCK
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.requireParameterIntact
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.extension.requireThisIntact
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.reels.doubletap.GESTURE_REACTION
import app.morphe.patches.facebook.reels.doubletap.MUTATE_LIKE
import app.morphe.patches.facebook.reels.doubletap.doubleTapLikes
import app.morphe.patches.facebook.reels.doubletap.hearts
import app.morphe.patches.facebook.reels.doubletap.isGestureReactionComponent
import app.morphe.patches.facebook.reels.doubletap.isReelLike
import app.morphe.patches.facebook.reels.doubletap.mountedView
import app.morphe.patches.facebook.reels.doubletap.turnOffDoubleTapLikePatch
import app.morphe.patches.facebook.reels.hold.SPEED_UP_LOG
import app.morphe.patches.facebook.reels.hold.holdReelFor2xPatch
import app.morphe.patches.facebook.reels.hold.isLongPressHandler
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Open a reel's comments with a double tap or a long press, and a feed post's with a long press
 * (issue #179). See GestureAnchors.kt for where each hook goes and what it relies on.
 *
 * It rides on two patches that are already there. Turn off double tap to like puts the hooks that
 * stop a double tap from liking or showing a heart, which the extension's DoubleTapLike also answers
 * for this patch's switch. Hold a reel for 2x hooks the long-press handler's speed-up path by
 * instruction index, so this patch goes in after it and puts its own hook at the handler's first
 * instruction, which moves nothing that one recorded.
 */
@Suppress("unused")
val openCommentsWithGesturePatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Open comments with a gesture",
    description = "Open a reel's comments with a double tap or a long press, on the sheet the Comment button opens, " +
        "instead of liking or holding, and a feed post's with a long press on its photo or name line. Starts off. " +
        "Turn it on in Hushfacebook settings > Reels and Watch. It also lets a long press on a post's text open " +
        "that text to select part of it, a switch in Hushfacebook settings > News feed. Another switch there makes " +
        "holding a post's photo or video open its reactions, or give one reaction you pick.",
) {
    category("Reels")
    dependsOn(settingsPatch, turnOffDoubleTapLikePatch, holdReelFor2xPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Every anchor is found, and every register this patch borrows is checked, before one
        // instruction changes. A build that fails anywhere in the find phase is refused untouched.
        requireStatusMethod("reelGestures")
        val anchors = findGestureAnchors()
        val clipboardWriters = clipboardWriters()
        val copyPopups = copyPopups(clipboardWriters)
        applyGestureAnchors(anchors)
        sendClipboardWrites(clipboardWriters)
        guardCopyPopups(copyPopups)
        enableStatus("reelGestures")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$GESTURE_PATCH: $detail")

/** What [findGestureAnchors] found, for [applyGestureAnchors] to change. */
internal class GestureAnchors(
    /** The Comment button object's class and its click handler, the static method taking a view, the object and a boolean. */
    val button: ClassDef,
    val click: Method,
    /** The button object's constructor, and the index among its parameters of the reel it is made for. */
    val constructor: Method,
    val itemParameter: Int,
    /** The reel like helper's double-tap like, and the gesture view's heart. */
    val doubleTapLike: Method,
    val heart: Method,
    /** Each long-press handler's invoke, and the Unit field it ends by returning. */
    val handlers: List<LongPressHook>,
    /** FbFragmentActivity's touch dispatch, where a feed post's long press is timed. */
    val dispatch: Method,
    /** The comment sheet parameters' constructor taking the builder, and the keyboard flag it sets. */
    val params: Method,
    val keyboard: FieldReference,
    /** How the click builds and shows the comment sheet, for a reel with no button object. */
    val sheet: SheetCalls,
    /** The story holder's constructor taking the reel and an Integer, for a handler that keeps no holder. */
    val holderInit: Method,
    /** The reaction tray's constructors, which hand each tray to Hold a post to react. */
    val docks: List<Method>,
)

/**
 * One long-press handler's `invoke` and the static field of the Unit it returns, and its constructor
 * with the index among its parameters of the reel it captures.
 */
internal class LongPressHook(val invoke: Method, val unit: FieldReference, val constructor: Method, val itemParameter: Int)

/**
 * The calls the Comment button's click makes to build the comment sheet and show it, and the log
 * names and number it hands the builder: [build] takes the context, the view, the session, the reel,
 * the reel's feedback (null here), the environment's [surface], the reel's props ([wrap] of [merge] of
 * the reel and its holder's [story]) and the viewer config among nulls. Each invoke is written in its
 * range form.
 */
internal class SheetCalls(
    val build: MethodReference,
    val show: MethodReference,
    val surface: MethodReference,
    val surfaceInvoke: String,
    val story: MethodReference,
    val storyInvoke: String,
    val merge: MethodReference,
    val wrap: MethodReference,
    val names: List<String>,
    val number: Int,
) {
    val environment: String get() = surface.definingClass
    val holder: String get() = story.definingClass
    val config: String get() = build.parameterTypes[CONFIG_ARGUMENT].toString()

    /** How many arguments the builder takes, and where its number goes. */
    val arguments: Int get() = build.parameterTypes.size
    val numberArgument: Int get() = numberArgumentOf(build)
}

/** Where [build] takes its number: right before the two booleans it ends with. */
private fun numberArgumentOf(build: MethodReference) = build.parameterTypes.size - 3

/**
 * Where the comment sheet's builder takes the reel's feedback, the surface, the props, the viewer
 * config and its two log names. 587 moved the feedback from argument 8 to 4, ahead of the surface
 * and the props, and left the rest where 586 had them.
 */
private const val FEEDBACK_ARGUMENT = 4
private const val SURFACE_ARGUMENT = 5
private const val PROPS_ARGUMENT = 6
private const val CONFIG_ARGUMENT = 11
private val NAME_ARGUMENTS = listOf(12, 13)

// 585 put a List after the four log names (the click passes null), so the builder takes 20 arguments
// with the number at 17.
private const val SHEET_BUILD_SIZE = 20

private val STATIC_INVOKES = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

private fun Instruction.methodReference() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.parameters() = parameterTypes.map(CharSequence::toString)

private fun MethodReference.key() = "$definingClass->$name${parameters().joinToString("", "(", ")")}$returnType"

/** The range form of a virtual or interface [call]'s opcode, or null for any other. */
private fun rangeInvoke(call: Instruction) = when (call.opcode) {
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> "invoke-virtual/range"
    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> "invoke-interface/range"
    else -> null
}

/**
 * The comment sheet's builder as [click] calls it, once, with its shape checked: the context, the
 * view, the session and the reel ([itemType]) first, the reel's feedback, the surface and the props
 * (objects), the two comments, the composer action and the search prompt, the config, four log names,
 * a List, then a number and two booleans.
 */
private fun isSheetBuild(call: MethodReference, itemType: String): Boolean {
    val types = call.parameters()
    return types.size == SHEET_BUILD_SIZE && types.take(4) == listOf(CONTEXT, VIEW, FB_USER_SESSION, itemType) &&
        listOf(FEEDBACK_ARGUMENT, SURFACE_ARGUMENT, PROPS_ARGUMENT, CONFIG_ARGUMENT).all { types[it].startsWith("L") } &&
        types[7] == GRAPHQL_COMMENT && types[8] == GRAPHQL_COMMENT && types[9] == COMPOSER_ACTION &&
        types[10] == SEARCH_PROMPT && types.subList(12, 16).all { it == STRING } && types[16] == LIST &&
        types.takeLast(3) == listOf("I", "Z", "Z") && call.returnType.startsWith("L")
}

/** The literal [code] last puts in [register] before [at], when that write is a constant; null otherwise. */
private fun literalBefore(code: List<Instruction>, at: Int, register: Int): Any? {
    for (index in at - 1 downTo 0) {
        val write = code[index]
        if (!write.opcode.setsRegister() || (write as? OneRegisterInstruction)?.registerA != register) continue
        return when (write.opcode) {
            Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> ((write as ReferenceInstruction).reference as StringReference).string
            Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST -> (write as NarrowLiteralInstruction).narrowLiteral
            else -> null
        }
    }
    return null
}

/** What [click] builds the comment sheet from, for the reel type [itemType]. Refuses unless every call is found once. */
internal fun sheetCalls(click: Method, itemType: String): SheetCalls {
    val code = click.implementation!!.instructions.toList()
    val where = "the Comment button's click"
    val builds = code.indices.filter { at ->
        code[at].opcode in STATIC_INVOKES && code[at].methodReference()?.let { isSheetBuild(it, itemType) } == true
    }
    val buildAt = builds.singleOrNull()
        ?: refuse("expected $where to build the comment sheet once, with a $COMPOSER_ACTION and a $SEARCH_PROMPT, found ${builds.size}")
    val build = code[buildAt].methodReference()!!
    val first = (code[buildAt] as? RegisterRangeInstruction)?.startRegister
        ?: refuse("$where builds the comment sheet without a register range")
    val names = NAME_ARGUMENTS.map { literalBefore(code, buildAt, first + it) as? String }
    val number = literalBefore(code, buildAt, first + numberArgumentOf(build)) as? Int
    if (names.any { it == null } || number == null) refuse("$where hands the comment sheet's builder no constant log names and number")

    fun distinct(what: String, matches: (Instruction, MethodReference) -> Boolean): Pair<Instruction, MethodReference> {
        val calls = code.mapNotNull { call -> call.methodReference()?.takeIf { matches(call, it) }?.let { call to it } }
            .distinctBy { it.second.key() }
        return calls.singleOrNull() ?: refuse("expected $where to make one $what, found ${calls.size}")
    }
    val (_, show) = distinct("call showing the sheet (${CONTEXT}, ${build.returnType})") { call, it ->
        call.opcode in STATIC_INVOKES && it.parameters() == listOf(CONTEXT, build.returnType)
    }
    val surfaceType = build.parameterTypes[SURFACE_ARGUMENT].toString()
    val propsType = build.parameterTypes[PROPS_ARGUMENT].toString()
    val (surfaceCall, surface) = distinct("call answering the sheet's surface ($surfaceType)") { call, it ->
        rangeInvoke(call) != null && it.parameterTypes.isEmpty() && it.returnType == surfaceType
    }
    val (mergeCall, merge) = distinct("static merging the reel and its story") { call, it ->
        call.opcode in STATIC_INVOKES && it.parameters() == listOf(itemType, GRAPHQL_STORY) && it.returnType == GRAPHQL_STORY
    }
    // The story comes from the holder right before the merge, and the merge goes straight into the wrap.
    val mergeAt = code.indexOf(mergeCall)
    val storyCall = code.getOrNull(mergeAt - 2)
    val story = storyCall?.methodReference()
    val wrap = code.getOrNull(mergeAt + 2)?.methodReference()
    if (storyCall == null || story == null || rangeInvoke(storyCall) == null || story.parameterTypes.isNotEmpty() ||
        story.returnType != GRAPHQL_STORY || code[mergeAt - 1].opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        refuse("$where doesn't ask the reel's story holder for its story right before merging it")
    }
    if (wrap == null || code[mergeAt + 2].opcode !in STATIC_INVOKES || wrap.parameters() !in listOf(listOf(GRAPHQL_STORY), listOf(OBJECT)) ||
        wrap.returnType != propsType || code[mergeAt + 1].opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        refuse("$where doesn't wrap the merged story as the sheet's props ($propsType) right after merging it")
    }
    return SheetCalls(
        build, show, surface, rangeInvoke(surfaceCall)!!, story, rangeInvoke(storyCall)!!, merge, wrap,
        names.map { it!! }, number!!,
    )
}

internal fun BytecodePatchContext.findGestureAnchors(): GestureAnchors {
    val likes = classDefByStrings(MUTATE_LIKE, StringComparisonType.EQUALS).flatMap { owner -> owner.methods.filter(::isReelLike) }
    val like = likes.singleOrNull()
        ?: refuse("expected one reel like holding \"$MUTATE_LIKE\", found ${likes.size}")
    val helper = classDefBy(like.definingClass)
    val doubleTapLike = doubleTapLikes(helper).singleOrNull()
        ?: refuse("expected the reel like helper to have one double-tap like taking two objects and a boolean")
    val itemType = doubleTapLike.parameterTypes[0].toString()

    val buttons = classDefByStrings(COMMENT_TAP, StringComparisonType.EQUALS).mapNotNull { owner ->
        owner.methods.singleOrNull { isCommentClick(it, owner) }?.let { owner to it }
    }
    val (button, click) = buttons.singleOrNull()
        ?: refuse("expected one class with a static click handler holding \"$COMMENT_TAP\" taking a view, itself and a boolean, found ${buttons.size}")
    // The extension's stub calls the handler from outside Facebook's package.
    if (!AccessFlags.PUBLIC.isSet(button.accessFlags) || !AccessFlags.PUBLIC.isSet(click.accessFlags)) {
        refuse("the Comment button's ${button.type}->${click.name} isn't public, so the extension can't call it")
    }
    val constructors = button.methods.filter { it.name == "<init>" }
    val constructor = constructors.singleOrNull() ?: refuse("expected ${button.type} to have one constructor, found ${constructors.size}")
    val items = constructor.parameterTypes.withIndex().filter { it.value.toString() == itemType }
    val itemParameter = items.singleOrNull()?.index
        ?: refuse("expected ${button.type}'s constructor to take the reel ($itemType) once, found ${items.size}")
    val returns = constructor.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) refuse("${button.type}'s constructor never returns")
    // The hook reads the object ({@code p0}) in a 4-bit register next to the scratch v0, which needs 1 local.
    if (constructor.localRegisterCount() !in 1..14) {
        refuse("${button.type}'s constructor has ${constructor.localRegisterCount()} local register(s), needs 1 to 14")
    }
    // Facebook's constructors reuse a register once they're done with what it held, the object's included.
    constructor.requireThisIntact(GESTURE_PATCH, returns)
    constructor.requireParameterIntact(GESTURE_PATCH, itemParameter, returns)
    val sheet = sheetCalls(click, itemType)
    val holderInit = holderInit(classDefByOrNull(sheet.holder) ?: refuse("this build has no ${sheet.holder}"), itemType)

    val components = classDefByStrings(GESTURE_REACTION, StringComparisonType.EQUALS).filter(::isGestureReactionComponent)
    val component = components.singleOrNull()
        ?: refuse("expected one class whose constructor holds \"$GESTURE_REACTION\", found ${components.size}")
    val viewType = mountedView(component) ?: refuse("$GESTURE_REACTION's onCreateMountContent doesn't make one view")
    val view = classDefByOrNull(viewType) ?: refuse("this build has no $viewType")
    val heart = hearts(view).singleOrNull()
        ?: refuse("expected the gesture view to have one static heart holding \"shortFormVideoUnit\"")

    val handlerClasses = classDefByStrings(SPEED_UP_LOG, StringComparisonType.EQUALS).filter(::isLongPressHandler)
    if (handlerClasses.isEmpty()) refuse("no long-press handler holds \"$SPEED_UP_LOG\"")
    // What the extension reads off a handler to open the sheet of a reel with no button, by name, and the type the helper casts it to.
    val kept = mapOf(
        ITEM_FIELD to itemType,
        SESSION_FIELD to FB_USER_SESSION,
        ENVIRONMENT_FIELD to sheet.environment,
        STORY_FIELD to sheet.holder,
        CONFIG_FIELD to sheet.config,
    )
    val handlers = handlerClasses.map { handler ->
        kept.forEach { (name, type) ->
            val field = handler.fields.singleOrNull { it.name == name && !AccessFlags.STATIC.isSet(it.accessFlags) }
            if (field?.type != type) refuse("the long-press handler ${handler.type} keeps no $name of type $type")
        }
        val invokes = handler.methods.filter(::isInvoke)
        val invoke = invokes.singleOrNull() ?: refuse("expected ${handler.type} to have one invoke(Object)Object, found ${invokes.size}")
        val units = unitReturns(invoke)
        val unit = units.singleOrNull()
            ?: refuse("expected ${handler.type}->invoke to return one Unit field, found ${units.size}")
        val made = handler.methods.filter { it.name == "<init>" }
        val init = made.singleOrNull() ?: refuse("expected ${handler.type} to have one constructor, found ${made.size}")
        val reels = init.parameterTypes.withIndex().filter { it.value.toString() == itemType }
        val reel = reels.singleOrNull()?.index
            ?: refuse("expected ${handler.type}'s constructor to take the reel ($itemType) once, found ${reels.size}")
        val ends = init.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        if (ends.isEmpty()) refuse("${handler.type}'s constructor never returns")
        if (init.localRegisterCount() !in 1..14) {
            refuse("${handler.type}'s constructor has ${init.localRegisterCount()} local register(s), needs 1 to 14")
        }
        init.requireThisIntact(GESTURE_PATCH, ends)
        init.requireParameterIntact(GESTURE_PATCH, reel, ends)
        LongPressHook(invoke, unit, init, reel)
    }

    val activity = classDefByOrNull(FRAGMENT_ACTIVITY) ?: refuse("this build has no $FRAGMENT_ACTIVITY")
    val dispatches = touchDispatches(activity)
    val dispatch = dispatches.singleOrNull()
        ?: refuse("expected $FRAGMENT_ACTIVITY to declare one dispatchTouchEvent(MotionEvent)Z, found ${dispatches.size}")

    val feedbackParams = classDefByOrNull(FEEDBACK_PARAMS) ?: refuse("this build has no $FEEDBACK_PARAMS")
    val keyboard = keyboardFlag(feedbackParams) ?: refuse("$FEEDBACK_PARAMS's toString reads no boolean after \"$KEYBOARD_LABEL\"")
    val builds = feedbackParams.methods.filter { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) != listOf(PARCEL) }
    val params = builds.singleOrNull()
        ?: refuse("expected $FEEDBACK_PARAMS to have one constructor besides the Parcel one, found ${builds.size}")
    keyboardStore(params, keyboard)
        ?: refuse("expected $FEEDBACK_PARAMS's constructor to store ${keyboard.name} once, from a register nothing reads after the store")
    val docks = dockConstructors(classDefByOrNull(UFI_DOCK) ?: refuse("this build has no $UFI_DOCK"))
    return GestureAnchors(button, click, constructor, itemParameter, doubleTapLike, heart, handlers, dispatch, params, keyboard, sheet, holderInit, docks)
}

/** Every constructor of the reaction tray, each still holding the tray in this at every return. */
internal fun dockConstructors(dock: ClassDef): List<Method> {
    val inits = dock.methods.filter { it.name == "<init>" }
    if (inits.isEmpty()) refuse("${dock.type} has no constructor")
    inits.forEach { init ->
        val ends = returnsOf(init)
        if (ends.isEmpty()) refuse("${dock.type}'s constructor never returns")
        init.requireThisIntact(GESTURE_PATCH, ends)
    }
    return inits
}

private fun returnsOf(method: Method): List<Int> =
    method.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }

/**
 * [holder]'s one public constructor taking the reel ([itemType]) and an Integer, which leaves the story
 * unset so the holder derives it from the reel when asked. The video controls' long-press handler, the
 * only one Clean mode makes, keeps no holder, so the sheet helper makes one with it.
 */
internal fun holderInit(holder: ClassDef, itemType: String): Method {
    if (!AccessFlags.PUBLIC.isSet(holder.accessFlags)) refuse("the story holder ${holder.type} isn't public, so the extension can't make one")
    val inits = holder.methods.filter {
        it.name == "<init>" && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.parameterTypes.map(CharSequence::toString) == listOf(itemType, INTEGER)
    }
    return inits.singleOrNull()
        ?: refuse("expected the story holder ${holder.type} to have one public constructor taking the reel ($itemType) and an Integer, found ${inits.size}")
}

internal fun BytecodePatchContext.applyGestureAnchors(anchors: GestureAnchors) {
    // The registers and the stub are checked before anything changes.
    val stub = mutableClassDefBy(REEL_GESTURES).methods.singleOrNull {
        it.name == OPEN_STUB && it.returnType == "Z" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, OBJECT, "Z")
    } ?: refuse("$REEL_GESTURES has no static Z $OPEN_STUB(Object, Object, boolean)")
    val sheetStub = mutableClassDefBy(REEL_GESTURES).methods.singleOrNull {
        it.name == SHEET_STUB && it.returnType == "Z" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == List(6) { OBJECT }
    } ?: refuse("$REEL_GESTURES has no static Z $SHEET_STUB taking six objects")
    val doubleTap = mutableClassDefBy(anchors.doubleTapLike.definingClass).findMutableMethodOf(anchors.doubleTapLike)
    doubleTap.requireLocals(GESTURE_PATCH, 1)
    val itemRegister = doubleTap.parameterRegister(0)
    val handlers = anchors.handlers.map { hook ->
        mutableClassDefBy(hook.invoke.definingClass).findMutableMethodOf(hook.invoke).also { it.requireLocals(GESTURE_PATCH, 1) }
    }

    // The Comment button's click handler, pressed from the extension.
    val button = anchors.button.type
    stub.addInstructions(
        0,
        """
            check-cast p0, $VIEW
            check-cast p1, $button
            invoke-static { p0, p1, p2 }, $button->${anchors.click.name}($VIEW${button}Z)V
            const/4 p0, 0x1
            return p0
        """,
    )

    // Each new Comment button object, with the reel it is made for, at every return of its constructor.
    val constructor = mutableClassDefBy(button).findMutableMethodOf(anchors.constructor)
    val item = constructor.parameterRegister(anchors.itemParameter)
    constructor.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        .asReversed().forEach { index ->
            constructor.addInstructionsAtControlFlowLabel(
                index,
                """
                    move-object/from16 v0, $item
                    invoke-static { p0, v0 }, $REMEMBER
                """,
            )
        }

    // A reel with no button object: its sheet built and shown the way the click does, from what its
    // long-press handler keeps, by a helper with registers of its own.
    val sheet = anchors.sheet
    val itemType = anchors.constructor.parameterTypes[anchors.itemParameter].toString()
    val helperTypes = listOf(VIEW, FB_USER_SESSION, itemType, sheet.environment, sheet.holder, sheet.config)
    val helper = "$REEL_GESTURES->$SHEET_HELPER(${helperTypes.joinToString("")})Z"
    mutableClassDefBy(REEL_GESTURES).methods.add(sheetHelper(sheet, helperTypes, anchors.holderInit))
    sheetStub.addInstructions(
        0,
        helperTypes.withIndex().joinToString("\n") { (index, type) -> "check-cast p$index, $type" } +
            """

                invoke-static/range { p0 .. p5 }, $helper
                move-result p0
                return p0
            """,
    )

    // Each long-press handler, with the reel it captured, at every return of its constructor.
    anchors.handlers.forEach { hook ->
        val made = mutableClassDefBy(hook.constructor.definingClass).findMutableMethodOf(hook.constructor)
        val reel = made.parameterRegister(hook.itemParameter)
        made.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            .asReversed().forEach { index ->
                made.addInstructionsAtControlFlowLabel(
                    index,
                    """
                        move-object/from16 v0, $reel
                        invoke-static { p0, v0 }, $REMEMBER_HANDLER
                    """,
                )
            }
    }

    // A double tap's reel, first thing in the reel like helper's double-tap like: taken here, it returns without liking.
    doubleTap.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, $itemRegister
            invoke-static { v0 }, $DOUBLE_TAP
            move-result v0
            if-eqz v0, :stock
            return-void
        """,
        ExternalLabel("stock", doubleTap.getInstruction(0)),
    )

    // The view a double tap landed on, first thing in the gesture view's heart (its second parameter).
    mutableClassDefBy(anchors.heart.definingClass).findMutableMethodOf(anchors.heart)
        .addInstruction(0, "invoke-static/range { p1 .. p1 }, $NOTE_VIEW")

    // A long press, first thing in each handler: taken here, it returns Unit, as the handler does when it has nothing to do.
    handlers.forEachIndexed { index, handler ->
        val unit = anchors.handlers[index].unit
        handler.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p1 }, $LONG_PRESS
                move-result v0
                if-eqz v0, :stock
                sget-object v0, ${unit.definingClass}->${unit.name}:${unit.type}
                return-object v0
            """,
            ExternalLabel("stock", handler.getInstruction(0)),
        )
    }

    // Each reaction tray, as its constructor returns, for Hold a post to react to click a chosen reaction in.
    anchors.docks.forEach { init ->
        val made = mutableClassDefBy(init.definingClass).findMutableMethodOf(init)
        returnsOf(made).asReversed().forEach { index ->
            made.addInstructionsAtControlFlowLabel(index, "invoke-static/range { p0 .. p0 }, $DOCK_MADE")
        }
    }

    // Every touch on a Facebook screen, with the activity, for a press held on a feed post. The
    // extension may turn the rest of a gesture it took into a cancel; the range form borrows no register.
    mutableClassDefBy(FRAGMENT_ACTIVITY).findMutableMethodOf(anchors.dispatch)
        .addInstruction(0, "invoke-static/range { p0 .. p1 }, $FEED_PRESS")

    // Whether each comment sheet raises the keyboard when it first loads, as the constructor that builds
    // its parameters stores it: the sheet a feed hold opened keeps it down. Only the register the store
    // reads changes; the constructor reuses the object's register before it returns.
    val params = mutableClassDefBy(FEEDBACK_PARAMS).findMutableMethodOf(anchors.params)
    val store = keyboardStore(params, anchors.keyboard)
        ?: refuse("$FEEDBACK_PARAMS's constructor no longer stores ${anchors.keyboard.name} once from a register it drops")
    val value = (params.implementation!!.instructions[store] as TwoRegisterInstruction).registerA
    params.addInstructionsAtControlFlowLabel(
        store,
        """
            invoke-static { v$value }, $KEYBOARD
            move-result v$value
        """,
    )
}

/**
 * The builder's arguments from the comments on, in v7 up to its last: the config, the click's own log
 * names and number, and null or false for the rest. The click passes sometimes a search prompt and null
 * for the List.
 */
private fun sheetArguments(sheet: SheetCalls): String = (PROPS_ARGUMENT + 1 until sheet.arguments).joinToString("\n") { argument ->
    val register = "v$argument"
    val zero = if (argument < 16) "const/4" else "const/16"
    when (argument) {
        CONFIG_ARGUMENT -> "move-object/from16 $register, p5"
        in NAME_ARGUMENTS -> {
            val name = sheet.names[NAME_ARGUMENTS.indexOf(argument)].replace("\\", "\\\\").replace("\"", "\\\"")
            "const-string $register, \"$name\""
        }
        sheet.numberArgument -> "const $register, ${sheet.number}"
        else -> "$zero $register, 0x0"
    }
}

/**
 * The extension's helper taking [types] (the view, the session, the reel, the environment, the
 * story holder and the config): it builds the comment sheet with the values the click passes, shows
 * it, and answers whether there was one. v0 up are the builder's arguments, one register each. With no story holder
 * it makes one from the reel with [holderInit], as Facebook does for a reel it has no story for yet;
 * v7 is free until the arguments fill it. The feedback (v4) goes in null: the click passes the
 * button's copy of the reel's feedback, but the builder takes the props' own when it gets none.
 */
private fun sheetHelper(sheet: SheetCalls, types: List<String>, holderInit: Method): MutableMethod {
    val build = sheet.build
    val surface = sheet.surface
    val story = sheet.story
    val holder = "${sheet.holder}-><init>(${holderInit.parameterTypes.joinToString("")})V"
    return ImmutableMethod(
        REEL_GESTURES,
        SHEET_HELPER,
        types.map { ImmutableMethodParameter(it, null, null) },
        "Z",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(sheet.arguments + types.size),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                move-object/from16 v1, p0
                invoke-virtual { v1 }, $VIEW->getContext()$CONTEXT
                move-result-object v0
                move-object/from16 v2, p1
                move-object/from16 v3, p2
                const/4 v4, 0x0
                move-object/from16 v5, p3
                ${sheet.surfaceInvoke} { v5 .. v5 }, ${surface.key()}
                move-result-object v5
                move-object/from16 v6, p4
                if-nez v6, :holder
                new-instance v6, ${sheet.holder}
                const/4 v7, 0x0
                invoke-direct { v6, v3, v7 }, $holder
                :holder
                ${sheet.storyInvoke} { v6 .. v6 }, ${story.key()}
                move-result-object v6
                invoke-static { v3, v6 }, ${sheet.merge.key()}
                move-result-object v6
                invoke-static { v6 }, ${sheet.wrap.key()}
                move-result-object v6
${sheetArguments(sheet)}
                invoke-static/range { v0 .. v${sheet.arguments - 1} }, ${build.key()}
                move-result-object v1
                if-eqz v1, :none
                invoke-static { v0, v1 }, ${sheet.show.key()}
                const/4 v0, 0x1
                return v0
                :none
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}

/**
 * The index of [params]' one store of [flag], when nothing reads the register it stores from once
 * the store has run, so a hook just before it can change that register for the store alone; null otherwise.
 */
internal fun keyboardStore(params: Method, flag: FieldReference): Int? {
    val code = params.implementation?.instructions?.toList() ?: return null
    val stores = code.indices.filter { index ->
        val field = (code[index] as? ReferenceInstruction)?.reference as? FieldReference
        code[index].opcode == Opcode.IPUT_BOOLEAN && field != null &&
            field.definingClass == flag.definingClass && field.name == flag.name && field.type == flag.type
    }
    val store = stores.singleOrNull() ?: return null
    val value = (code[store] as TwoRegisterInstruction).registerA
    return store.takeIf { params.readsAfter(store, value).isEmpty() }
}

/** The boolean field [params]' toString appends after [KEYBOARD_LABEL], the flag that raises the comment sheet's keyboard. */
internal fun keyboardFlag(params: ClassDef): FieldReference? {
    val code = params.methods.singleOrNull { it.name == "toString" && it.parameterTypes.isEmpty() }
        ?.implementation?.instructions?.toList() ?: return null
    val label = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == KEYBOARD_LABEL }
    if (label < 0) return null
    val read = code.drop(label + 1).firstOrNull { it.opcode == Opcode.IGET_BOOLEAN } ?: return null
    val field = (read as ReferenceInstruction).reference as FieldReference
    return field.takeIf { it.definingClass == params.type && it.type == "Z" && params.fields.any { declared -> declared.name == it.name } }
}

/** The static Unit fields [invoke] returns: a static read of a field of its own type, returned straight away. */
internal fun unitReturns(invoke: Method): List<FieldReference> {
    val code = invoke.implementation?.instructions?.toList().orEmpty()
    return code.withIndex().mapNotNull { (index, instruction) ->
        val next = code.getOrNull(index + 1) ?: return@mapNotNull null
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode != Opcode.SGET_OBJECT || field == null || field.type != field.definingClass ||
            next.opcode != Opcode.RETURN_OBJECT ||
            (next as OneRegisterInstruction).registerA != (instruction as OneRegisterInstruction).registerA
        ) {
            null
        } else {
            field
        }
    }.distinctBy { it.toString() }
}

internal fun isInvoke(method: Method): Boolean =
    method.name == "invoke" && !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == OBJECT &&
        method.parameterTypes.map(CharSequence::toString) == listOf(OBJECT) && method.implementation != null

/** The Comment button's click handler: static, taking a view, its own class and a boolean, holding [COMMENT_TAP]. */
internal fun isCommentClick(method: Method, owner: ClassDef): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(VIEW, owner.type, "Z") &&
        app.morphe.patches.facebook.feed.holdsString(method, COMMENT_TAP)
