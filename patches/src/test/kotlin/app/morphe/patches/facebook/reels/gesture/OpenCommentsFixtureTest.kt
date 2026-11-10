/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.requireThisIntact
import app.morphe.patches.facebook.reels.doubletap.GESTURE_REACTION
import app.morphe.patches.facebook.reels.doubletap.HEART_RISE
import app.morphe.patches.facebook.reels.doubletap.MUTATE_LIKE
import app.morphe.patches.facebook.reels.doubletap.doubleTapLikes
import app.morphe.patches.facebook.reels.doubletap.feedPhotoFixtureClasses
import app.morphe.patches.facebook.reels.doubletap.hearts
import app.morphe.patches.facebook.reels.doubletap.isAttachmentDoubleTap
import app.morphe.patches.facebook.reels.doubletap.isGestureReactionComponent
import app.morphe.patches.facebook.reels.doubletap.isReelLike
import app.morphe.patches.facebook.reels.doubletap.mountedView
import app.morphe.patches.facebook.reels.doubletap.turnOffDoubleTapLikePatch
import app.morphe.patches.facebook.reels.doubletap.typesMadeBy
import app.morphe.patches.facebook.reels.hold.SPEED_UP_LOG
import app.morphe.patches.facebook.reels.hold.isLongPressHandler
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Open comments with a gesture (#179) on every Facebook build the bundle declares (arm64 and
 * armv7): one Comment button object whose static click handler holds "tap_comment_button" and whose
 * constructor takes the reel the double-tap like takes, the reel like helper's double-tap like, the
 * gesture view's heart, and the long-press handlers with their Unit return, plus for Clean mode the
 * click's own sheet calls and what each handler keeps for them. The patch's hooks are put
 * on each build's own classes after Turn off double tap to like has changed the same helper, view and
 * heart, which is the order the patch runs in. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR
 * and skips without it.
 */
class OpenCommentsFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private val Instruction.field: FieldReference?
        get() = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun key(method: MethodReference) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun sameMethod(a: Method, b: Method) = key(a) == key(b)

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private class Found(
        val helper: ClassDef,
        val doubleTapLike: Method,
        val component: ClassDef,
        val view: ClassDef,
        val heart: Method,
        val button: ClassDef,
        val click: Method,
        val handlers: List<ClassDef>,
        val attachmentClass: ClassDef,
        val owners: List<ClassDef>,
        val activity: ClassDef,
        val feedbackParams: ClassDef,
        val holder: ClassDef,
    )

    /** The classes the patch reads in [bundle], each the only one of its kind. */
    private fun read(bundle: File): Found {
        val name = bundle.name
        val helpers = FixtureDex.classesHolding(bundle, MUTATE_LIKE)
        val like = helpers.flatMap { it.methods }.filter(::isReelLike).also {
            assertEquals("$name: reel likes holding \"$MUTATE_LIKE\"", 1, it.size)
        }.single()
        val helper = helpers.single { it.type == like.definingClass }
        val doubleTapLike = doubleTapLikes(helper).also { assertEquals("$name: double-tap likes", 1, it.size) }.single()

        val component = FixtureDex.classesHolding(bundle, GESTURE_REACTION).filter(::isGestureReactionComponent)
            .also { assertEquals("$name: gesture components", 1, it.size) }.single()
        val viewType = mountedView(component) ?: throw AssertionError("$name: the component makes no one view")
        val view = FixtureDex.classes(bundle, setOf(viewType)).getValue(viewType)
        val heart = hearts(view).also { assertEquals("$name: hearts", 1, it.size) }.single()
        val ownerTypes = setOf(viewType) + typesMadeBy(view) + typesMadeBy(component)
        val owners = FixtureDex.classes(bundle, ownerTypes).values.toList()

        val buttons = FixtureDex.classesHolding(bundle, COMMENT_TAP).mapNotNull { owner ->
            owner.methods.singleOrNull { isCommentClick(it, owner) }?.let { owner to it }
        }
        assertEquals("$name: classes with a click handler holding \"$COMMENT_TAP\"", 1, buttons.size)
        val (button, click) = buttons.single()

        val handlers = FixtureDex.classesHolding(bundle, SPEED_UP_LOG).filter(::isLongPressHandler)
        assertTrue("$name: no long-press handler", handlers.isNotEmpty())

        val attachmentClass = FixtureDex.classesHolding(bundle, HEART_RISE).single { owner ->
            owner.methods.any { isAttachmentDoubleTap(it, doubleTapLike) }
        }
        val activity = FixtureDex.classes(bundle, setOf(FRAGMENT_ACTIVITY)).getValue(FRAGMENT_ACTIVITY)
        assertEquals("$name: FbFragmentActivity's touch dispatches", 1, touchDispatches(activity).size)
        val feedbackParams = FixtureDex.classes(bundle, setOf(FEEDBACK_PARAMS)).getValue(FEEDBACK_PARAMS)
        val holderType = sheetCalls(click, doubleTapLike.parameterTypes[0].toString()).holder
        val holder = FixtureDex.classes(bundle, setOf(holderType)).getValue(holderType)
        return Found(helper, doubleTapLike, component, view, heart, button, click, handlers, attachmentClass, owners, activity, feedbackParams, holder)
    }

    @Test
    fun `on each declared build the anchors are found and unique`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val found = read(bundle)
                val itemType = found.doubleTapLike.parameterTypes[0].toString()

                // Positive control: the Comment button object is public, its click handler public, and its
                // constructor takes the reel's type once.
                assertTrue("$name: ${found.button.type} isn't public", AccessFlags.PUBLIC.isSet(found.button.accessFlags))
                assertTrue("$name: the click handler isn't public", AccessFlags.PUBLIC.isSet(found.click.accessFlags))
                val constructor = found.button.methods.single { it.name == "<init>" }
                assertEquals("$name: the constructor takes the reel once", 1,
                    constructor.parameterTypes.count { it.toString() == itemType })
                assertTrue("$name: the constructor needs 1 to 14 locals", constructor.localRegisterCount() in 1..14)
                assertTrue("$name: the constructor returns", constructor.code().any { it.opcode == Opcode.RETURN_VOID })

                // The heart is static (the event, the view); the double-tap like has a free local.
                assertEquals("$name: the heart's parameters", listOf(MOTION_EVENT, found.view.type),
                    found.heart.parameterTypes.map(CharSequence::toString))
                assertTrue("$name: the double-tap like has no free local", found.doubleTapLike.localRegisterCount() >= 1)

                // Each long-press handler: the reel it captured, one invoke, one Unit return, a free local.
                for (handler in found.handlers) {
                    assertTrue("$name: ${handler.type} keeps no $ITEM_FIELD", handler.fields.any { it.name == ITEM_FIELD })
                    val invoke = handler.methods.single(::isInvoke)
                    assertEquals("$name: ${handler.type}'s Unit returns", 1, unitReturns(invoke).size)
                    assertTrue("$name: ${handler.type}'s invoke has no free local", invoke.localRegisterCount() >= 1)
                }
                // The control: the click handler is the only static method of the button that holds the log name.
                assertEquals("$name: click handlers", 1, found.button.methods.count { isCommentClick(it, found.button) })

                // Clean mode: how the click builds and shows the sheet, with its own log names and number,
                // and each handler keeps what that takes and has one constructor taking the reel once.
                val sheet = sheetCalls(found.click, itemType)
                assertEquals("$name: the click's log names", listOf("unknown", "vdd_comment_button"), sheet.names)
                assertEquals("$name: the click's number", 0x18, sheet.number)
                assertEquals("$name: the sheet is shown from what was built", sheet.build.returnType, sheet.show.parameterTypes[1].toString())
                assertEquals("$name: the story is merged with the reel", sheet.story.returnType, sheet.merge.parameterTypes[1].toString())
                assertTrue("$name: the merged story fits the wrap",
                    sheet.wrap.parameterTypes.single().toString() in setOf(sheet.merge.returnType, OBJECT))
                val kept = mapOf(ITEM_FIELD to itemType, SESSION_FIELD to FB_USER_SESSION, ENVIRONMENT_FIELD to sheet.environment,
                    STORY_FIELD to sheet.holder, CONFIG_FIELD to sheet.config)
                for (handler in found.handlers) {
                    kept.forEach { (field, type) ->
                        assertEquals("$name: ${handler.type}'s $field", type, handler.fields.singleOrNull { it.name == field }?.type)
                    }
                    val init = handler.methods.single { it.name == "<init>" }
                    assertEquals("$name: ${handler.type}'s constructor takes the reel once", 1,
                        init.parameterTypes.count { it.toString() == itemType })
                    assertTrue("$name: ${handler.type}'s constructor needs 1 to 14 locals", init.localRegisterCount() in 1..14)
                }
                // The controls' handler keeps no story holder, so the helper makes one: the holder has one
                // public (reel, Integer) constructor, and its story getter derives a story from the reel.
                val holderInit = holderInit(found.holder, itemType)
                assertEquals("$name: the holder's constructor", listOf(itemType, INTEGER), holderInit.parameterTypes.map(CharSequence::toString))
                val getter = found.holder.methods.single { key(it) == key(sheet.story) }
                assertTrue("$name: the holder's story getter derives no story from the reel", getter.code().any { call ->
                    call.opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) &&
                        call.call?.let { it.parameterTypes.firstOrNull()?.toString() == itemType && it.returnType == GRAPHQL_STORY } == true
                })

                // The keyboard flag toString labels is a final boolean of the sheet's parameters, and it is
                // the one the sheet's first-load keyboard step reads.
                val keyboard = keyboardFlag(found.feedbackParams) ?: throw AssertionError("$name: no boolean after \"$KEYBOARD_LABEL\"")
                val declared = found.feedbackParams.fields.single { it.name == keyboard.name }
                assertTrue("$name: ${keyboard.name} isn't final", AccessFlags.FINAL.isSet(declared.accessFlags))
                val firstLoads = FixtureDex.classesHolding(bundle, KEYBOARD_STEP).flatMap { it.methods }
                    .filter { app.morphe.patches.facebook.feed.holdsString(it, KEYBOARD_STEP) }
                assertEquals("$name: methods holding \"$KEYBOARD_STEP\"", 1, firstLoads.size)
                assertTrue("$name: the first-load keyboard step doesn't read ${keyboard.name}",
                    firstLoads.single().code().any { it.opcode == Opcode.IGET_BOOLEAN && it.field.toString() == keyboard.toString() })
                val builds = found.feedbackParams.methods.filter { it.name == "<init>" }
                assertEquals("$name: FeedbackParams' constructors", 2, builds.size)
                val build = builds.single { it.parameterTypes.map(CharSequence::toString) != listOf(PARCEL) }
                val store = keyboardStore(build, keyboard)
                assertNotNull("$name: the builder constructor doesn't store ${keyboard.name} once from a register it drops", store)
                assertTrue("$name: the store isn't on the object the constructor made",
                    (build.code()[store!!] as TwoRegisterInstruction).registerB == build.localRegisterCount())
                // The control: the phone builds' constructor reuses the object's register before it returns,
                // so a hook reading the flag back at a return verifies on no device, and the guard says so.
                // The Android 9 build's keeps its object to the end, and the guard lets that one through.
                val returnAt = build.code().withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
                assertTrue("$name: the builder constructor never returns", returnAt.isNotEmpty())
                val android9 = name.substringAfter("-$version-").contains("-minapi")
                assertEquals("$name: whether the guard finds the builder constructor's object intact at its returns",
                    android9, runCatching { build.requireThisIntact("control", returnAt) }.isSuccess)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `on each declared build the patch applies after Turn off double tap to like and every hook sits where it's claimed`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val found = read(bundle)
                val classes = (listOf(found.helper, found.component, found.view, found.attachmentClass, found.button, found.activity, found.feedbackParams, found.holder) +
                    found.owners + found.handlers + feedPhotoFixtureClasses(bundle)).distinctBy { it.type } +
                    ExtensionDex.classDef(SETTINGS_STATUS) + ExtensionDex.classDef(REEL_GESTURES)
                val context = PatchContexts.of(classes)
                turnOffDoubleTapLikePatch.execute(context)

                // The old patch changed the helper, the view and the heart; what this one needs is still found.
                val anchors = context.findGestureAnchors()
                assertEquals("$name: the button", found.button.type, anchors.button.type)
                assertEquals("$name: the heart", found.heart.name, anchors.heart.name)
                assertEquals("$name: the handlers", found.handlers.map { it.type }.toSet(),
                    anchors.handlers.map { it.invoke.definingClass }.toSet())
                val before = anchors.handlers.associate { it.invoke.definingClass to it.invoke.code() }
                val madeBefore = anchors.handlers.associate { it.constructor.definingClass to it.constructor.code() }
                val heartBefore = anchors.heart.code()
                val doubleTapBefore = anchors.doubleTapLike.code()
                val constructorBefore = anchors.constructor.code()
                val dispatchBefore = anchors.dispatch.code()
                val paramsBefore = anchors.params.code()
                val storeBefore = keyboardStore(anchors.params, anchors.keyboard)!!
                context.applyGestureAnchors(anchors)

                fun patched(method: Method): Method =
                    context.mutableClassDefBy(method.definingClass).methods.single { sameMethod(it, method) }

                // A double tap: the reel copied down and asked about before anything else runs.
                val doubleTap = patched(anchors.doubleTapLike).code()
                assertEquals("$name: the double tap's first instruction", Opcode.MOVE_OBJECT_FROM16, doubleTap[0].opcode)
                assertEquals("$name: the reel the double tap hands on",
                    anchors.doubleTapLike.localRegisterCount() + 1, (doubleTap[0] as TwoRegisterInstruction).registerB)
                assertEquals("$name: the double tap's question", DOUBLE_TAP, doubleTap[1].call.toString())
                assertEquals("$name: shape of the double tap's hook",
                    listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                    doubleTap.take(5).map { it.opcode })
                assertEquals("$name: the rest of the double tap like is Facebook's",
                    doubleTapBefore.map { it.opcode }, doubleTap.drop(5).map { it.opcode })

                // The heart: the view first, the rest untouched.
                val heart = patched(anchors.heart).code()
                assertEquals("$name: the heart's first call", NOTE_VIEW, heart[0].call.toString())
                // An inserted instruction can add or drop the alignment NOP before the heart's array payload.
                assertEquals("$name: the heart's rest", heartBefore.map { it.opcode }.filter { it != Opcode.NOP },
                    heart.drop(1).map { it.opcode }.filter { it != Opcode.NOP })

                // The button's constructor: the reel and the object before every return.
                val constructor = patched(anchors.constructor).code()
                val returns = constructorBefore.count { it.opcode == Opcode.RETURN_VOID }
                assertEquals("$name: constructor hooks", returns, constructor.count { it.call.toString() == REMEMBER })
                assertEquals("$name: constructor instructions added", constructorBefore.size + 2 * returns, constructor.size)
                constructor.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.forEach { (index, _) ->
                    assertEquals("$name: remember sits before the return", REMEMBER, constructor[index - 1].call.toString())
                    assertEquals("$name: the reel is copied first", Opcode.MOVE_OBJECT_FROM16, constructor[index - 2].opcode)
                }

                // Each long-press handler asks first, then returns the Unit it returned before.
                for (hook in anchors.handlers) {
                    val handler = patched(hook.invoke).code()
                    assertEquals("$name: ${hook.invoke.definingClass}'s question", LONG_PRESS, handler[0].call.toString())
                    assertEquals("$name: shape of the long-press hook",
                        listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT),
                        handler.take(5).map { it.opcode })
                    assertEquals("$name: the Unit it returns", hook.unit.toString(), handler[3].field.toString())
                    assertEquals("$name: the rest of the handler is Facebook's",
                        before.getValue(hook.invoke.definingClass).map { it.opcode }, handler.drop(5).map { it.opcode })

                    // Its constructor: the handler and its reel before every return.
                    val made = patched(hook.constructor).code()
                    val madeOriginal = madeBefore.getValue(hook.constructor.definingClass)
                    val ends = madeOriginal.count { it.opcode == Opcode.RETURN_VOID }
                    assertEquals("$name: ${hook.constructor.definingClass}'s constructor hooks", ends,
                        made.count { it.call.toString() == REMEMBER_HANDLER })
                    assertEquals("$name: handler constructor instructions added", madeOriginal.size + 2 * ends, made.size)
                    made.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.forEach { (index, _) ->
                        assertEquals("$name: rememberHandler sits before the return", REMEMBER_HANDLER, made[index - 1].call.toString())
                        assertEquals("$name: the reel is copied first", Opcode.MOVE_OBJECT_FROM16, made[index - 2].opcode)
                        assertEquals("$name: the reel copied is the one the constructor takes",
                            hook.constructor.localRegisterCount() + 1 + hook.itemParameter,
                            (made[index - 2] as TwoRegisterInstruction).registerB)
                    }
                }

                // Clean mode: the helper the patch adds builds the sheet with the click's calls in one
                // range, then shows it, and the stub hands it its six objects.
                val sheet = anchors.sheet
                val helper = context.mutableClassDefBy(REEL_GESTURES).methods.single { it.name == SHEET_HELPER }
                val itemType = anchors.doubleTapLike.parameterTypes[0].toString()
                assertEquals("$name: the helper's parameters",
                    listOf(VIEW, FB_USER_SESSION, itemType, sheet.environment, sheet.holder, sheet.config),
                    helper.parameterTypes.map(CharSequence::toString))
                assertTrue("$name: the helper isn't public static",
                    AccessFlags.PUBLIC.isSet(helper.accessFlags) && AccessFlags.STATIC.isSet(helper.accessFlags))
                val helperCode = helper.code()
                val buildCall = helperCode.single { it.call?.let(::key) == key(sheet.build) } as RegisterRangeInstruction
                assertEquals("$name: the builder's range", listOf(0, sheet.build.parameterTypes.size),
                    listOf(buildCall.startRegister, buildCall.registerCount))
                // The number sits right before the two booleans, wherever 585's List pushed it.
                val numberWrite = helperCode.subList(0, helperCode.indexOf(buildCall as Instruction)).last {
                    (it as? OneRegisterInstruction)?.registerA == sheet.numberArgument
                }
                assertEquals("$name: the helper's number", sheet.number, (numberWrite as NarrowLiteralInstruction).narrowLiteral)
                val calls = helperCode.mapNotNull { it.call?.let(::key) }
                listOf(sheet.surface, sheet.story, sheet.merge, sheet.wrap, sheet.build, sheet.show).map(::key).forEach {
                    assertTrue("$name: the helper never calls $it", it in calls)
                }
                assertEquals("$name: the helper's log names", sheet.names,
                    helperCode.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string })
                // With no story holder, the helper makes one from the reel right before asking it for the story.
                val storyAt = helperCode.indexOfFirst { it.call?.let(::key) == key(sheet.story) }
                assertEquals("$name: the holder made before the story is asked for",
                    listOf(Opcode.IF_NEZ, Opcode.NEW_INSTANCE, Opcode.CONST_4, Opcode.INVOKE_DIRECT),
                    helperCode.subList(storyAt - 4, storyAt).map { it.opcode })
                assertEquals("$name: the holder made", sheet.holder, (helperCode[storyAt - 3] as ReferenceInstruction).reference.toString())
                assertEquals("$name: the holder's constructor called", key(anchors.holderInit), helperCode[storyAt - 1].call?.let(::key))
                assertEquals("$name: the reel and a null Integer handed to it", listOf(5, 3, 6),
                    (helperCode[storyAt - 1] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE) })
                val sheetStub = context.mutableClassDefBy(REEL_GESTURES).methods.single { it.name == SHEET_STUB }.code()
                val handOff = sheetStub.single { it.call?.name == SHEET_HELPER } as RegisterRangeInstruction
                assertEquals("$name: the stub hands the helper its six objects", 6, handOff.registerCount)
                assertEquals("$name: the stub casts each object first", 6, sheetStub.count { it.opcode == Opcode.CHECK_CAST })

                // Every touch: the activity and the event (p0 and p1) handed over first, the rest Facebook's.
                val dispatch = patched(anchors.dispatch)
                val touch = dispatch.code()
                assertEquals("$name: the touch hook", FEED_PRESS, touch[0].call.toString())
                val range = touch[0] as RegisterRangeInstruction
                assertEquals("$name: the touch hook's registers", listOf(dispatch.implementation!!.registerCount - 2, 2),
                    listOf(range.startRegister, range.registerCount))
                assertEquals("$name: the rest of the dispatch is Facebook's", dispatchBefore.map { it.opcode },
                    touch.drop(1).map { it.opcode })

                // The sheet's parameters: the value the constructor stores in the keyboard flag, asked about
                // right before the store, in the store's own register and no other.
                val params = patched(anchors.params).code()
                assertEquals("$name: keyboard hooks", 1, params.count { it.call.toString() == KEYBOARD })
                assertEquals("$name: keyboard instructions added", paramsBefore.size + 2, params.size)
                val hook = params.indexOfFirst { it.call.toString() == KEYBOARD }
                assertEquals("$name: the hook sits where the store was", storeBefore, hook)
                assertEquals("$name: shape of the keyboard hook", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IPUT_BOOLEAN),
                    params.subList(hook, hook + 3).map { it.opcode })
                val value = (paramsBefore[storeBefore] as TwoRegisterInstruction).registerA
                val ask = params[hook] as FiveRegisterInstruction
                assertEquals("$name: the hook asks about the stored value alone", listOf(1, value), listOf(ask.registerCount, ask.registerC))
                assertEquals("$name: the answer goes where the store reads", value, (params[hook + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the store is Facebook's", listOf(anchors.keyboard.toString(), value),
                    listOf(params[hook + 2].field.toString(), (params[hook + 2] as TwoRegisterInstruction).registerA))
                assertEquals("$name: the rest of the constructor is Facebook's", paramsBefore.map { it.opcode },
                    (params.take(hook) + params.drop(hook + 2)).map { it.opcode })

                // The stub in the extension presses the button's click handler.
                val stub = context.mutableClassDefBy(REEL_GESTURES).methods.single { it.name == OPEN_STUB }.code()
                assertEquals("$name: the stub's press", "${found.button.type}->${found.click.name}", stub.first { it.opcode == Opcode.INVOKE_STATIC }.call!!.let { it.definingClass + "->" + it.name })
                assertEquals("$name: the stub answers true", 1, (stub.first { it.opcode == Opcode.CONST_4 } as NarrowLiteralInstruction).narrowLiteral)

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "doubleTapLike" }
                assertEquals("$name: the old patch's status is on", 1, (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `the extension has the hooks`() {
        val extension = ExtensionDex.classDef(REEL_GESTURES)
        fun shape(name: String): String {
            val method = extension.methods.singleOrNull { it.name == name }
            assertNotNull("ReelGestures has no $name", method)
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(method!!.accessFlags))
            return "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
        }
        assertEquals(REMEMBER, shape("remember"))
        assertEquals(NOTE_VIEW, shape("noteView"))
        assertEquals(DOUBLE_TAP, shape("doubleTap"))
        assertEquals(LONG_PRESS, shape("longPress"))
        assertEquals("$REEL_GESTURES->$OPEN_STUB(Ljava/lang/Object;Ljava/lang/Object;Z)Z", shape(OPEN_STUB))
        assertEquals(REMEMBER_HANDLER, shape("rememberHandler"))
        assertEquals("$REEL_GESTURES->$SHEET_STUB(${OBJECT.repeat(6)})Z", shape(SHEET_STUB))

        val feed = ExtensionDex.classDef("$EXTENSION_PACKAGE/reels/PostLongPress;")
        val touch = feed.methods.singleOrNull {
            it.name == "touch" && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.parameterTypes.firstOrNull()?.toString() == "Landroid/app/Activity;"
        }
        assertNotNull("PostLongPress has no public touch(Activity, MotionEvent)", touch)
        assertTrue("touch isn't static", AccessFlags.STATIC.isSet(touch!!.accessFlags))
        assertEquals(FEED_PRESS, "${touch.definingClass}->${touch.name}(${touch.parameterTypes.joinToString("")})${touch.returnType}")

        val keyboard = feed.methods.singleOrNull { it.name == "keyboardOnFirstLoad" }
        assertNotNull("PostLongPress has no keyboardOnFirstLoad", keyboard)
        assertTrue("keyboardOnFirstLoad isn't public static",
            AccessFlags.PUBLIC.isSet(keyboard!!.accessFlags) && AccessFlags.STATIC.isSet(keyboard.accessFlags))
        assertEquals(KEYBOARD, "${keyboard.definingClass}->${keyboard.name}(${keyboard.parameterTypes.joinToString("")})${keyboard.returnType}")
    }

    private companion object {
        /** The log name of the comment sheet's first-load keyboard step, which reads the flag the patch sets. */
        const val KEYBOARD_STEP = "FeedbackFragment.maybeShowKeyboardOnFirstLoad"
    }
}
