/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Open Messenger from the top bar that need no Facebook build: which methods the
 * anchors take as the Messenger icon's tap and as the Messenger button handler it shares, which
 * they turn down, the code the hook puts first in both, and the touch hook in the screen's
 * dispatch.
 */
class MessengerIconShapesTest {
    private val contextType = "Landroid/content/Context;"
    private val sessionType = "Lcom/facebook/auth/usersession/FbUserSession;"
    private val tapParameters = listOf(
        contextType, sessionType, "Lfixture/Logger;", "Lfixture/Funnel;", "Lfixture/Eligibility;", "Lfixture/Launcher;",
        "Ljava/lang/String;", "Z", "Z", "Z",
    )
    private val handlerCall = ImmutableMethodReference("Lfixture/MessengerButton;", "A00", BUTTON_PARAMETERS, "V")

    private fun string(value: String) = ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(value))

    private val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)

    private fun method(
        name: String,
        parameters: List<String>,
        registers: Int,
        body: List<Instruction>,
        static: Boolean = true,
        returnType: String = "V",
        owner: String = "Lfixture/MessengerIcon;",
    ): Method = ImmutableMethod(
        owner,
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
        null,
        null,
        ImmutableMethodImplementation(registers, body, null, null),
    )

    /** The icon's tap: loads [literals], both entry points by default, and hands the tap to [calls]. */
    private fun tap(
        vararg literals: String = arrayOf(NAVBAR_ENTRY, REELS_TAB_ENTRY),
        parameters: List<String> = tapParameters,
        static: Boolean = true,
        returnType: String = "V",
        registers: Int = 18,
        calls: List<Instruction> = emptyList(),
    ) = method("A01", parameters, registers, literals.map(::string) + calls + returnVoid, static, returnType)

    /** The Messenger button handler: loads [literals], "long_press" by default. */
    private fun handler(
        vararg literals: String = arrayOf(LONG_PRESS),
        parameters: List<String> = BUTTON_PARAMETERS,
        static: Boolean = true,
        registers: Int = 25,
        owner: String = "Lfixture/MessengerIcon;",
    ) = method("A00", parameters, registers, literals.map(::string) + returnVoid, static, owner = owner)

    private fun invoke(opcode: Opcode, reference: MethodReference) =
        ImmutableInstruction35c(opcode, reference.parameterTypes.size, 3, 4, 7, 8, 9, reference)

    @Test
    fun `the tap is the static method over the context and session that loads both entry points`() {
        assertTrue(isIconTap(tap()))
        assertFalse(isIconTap(tap(NAVBAR_ENTRY)))
        assertFalse(isIconTap(tap(REELS_TAB_ENTRY)))
        assertFalse(isIconTap(tap(static = false)))
        assertFalse(isIconTap(tap(returnType = "Z")))
        // Another shape: a method missing the surface, the three flags, or the context in front.
        assertFalse(isIconTap(tap(parameters = tapParameters.dropLast(1))))
        assertFalse(isIconTap(tap(parameters = tapParameters.map { if (it == "Ljava/lang/String;") "Lfixture/Surface;" else it })))
        assertFalse(isIconTap(tap(parameters = listOf(sessionType, contextType) + tapParameters.drop(2))))
        assertFalse(isIconTap(tap(parameters = tapParameters.dropLast(3) + listOf("Z", "I", "Z"))))
    }

    private val shape583 = HANDLER_SHAPES[1]
    private val logHelperCall = ImmutableMethodReference("Lfixture/MessengerIcon;", "A00",
        listOf(contextType, sessionType, "Ljava/lang/String;", "Ljava/lang/String;", "Z"), "Ljava/lang/String;")

    /** 583's long-press log: a static helper of the handler's class answering a String, loading [literal]. */
    private fun logHelper(literal: String = LONG_PRESS) = method("A00", logHelperCall.parameterTypes.map { it.toString() }, 14,
        listOf(string(literal), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), returnType = "Ljava/lang/String;")

    /** 583's handler: a String after the entry point, handing its long press to [logHelperCall]. */
    private fun handler583(owner: String = "Lfixture/MessengerIcon;", call: MethodReference = logHelperCall) =
        method("A01", shape583, 30, listOf(invoke(Opcode.INVOKE_STATIC, call), returnVoid), owner = owner)

    @Test
    fun `the button handler is the static handler-shaped void that logs long_press, itself or through its class's helper`() {
        val owner = "Lfixture/MessengerIcon;"
        fun handles(handler: Method, vararg others: Method) = isButtonHandler(handler, classDef(owner, handler, *others))
        assertTrue(handles(handler()))
        assertFalse(handles(handler(literals = arrayOf())))
        assertFalse(handles(handler("is_messenger_installed")))
        assertFalse(handles(handler(static = false)))
        assertFalse(handles(handler(parameters = BUTTON_PARAMETERS.dropLast(1), registers = 24)))
        assertFalse(handles(method("A00", BUTTON_PARAMETERS, 25, listOf(string(LONG_PRESS), returnVoid), returnType = "Z")))
        assertEquals("the long press, up to 582", 3, buttonLongPress(handler()))

        // 583: a second String after the entry point, and "long_press" in a helper the handler calls.
        assertTrue(handles(handler583(), logHelper()))
        assertEquals("A00", longPressLog(handler583(), classDef(owner, handler583(), logHelper()))?.name)
        assertEquals("the long press on 583", 4, buttonLongPress(handler583()))
        assertFalse("a helper that doesn't log it", handles(handler583(), logHelper("is_messenger_installed")))
        assertFalse("no helper in the class", handles(handler583()))
        val foreign = ImmutableMethodReference("Lfixture/Other;", "A00", logHelperCall.parameterTypes, "Ljava/lang/String;")
        assertFalse("a helper in another class", handles(handler583(call = foreign), logHelper()))
    }

    @Test
    fun `the tap names the button handler by its static or virtual calls of that shape`() {
        val other = ImmutableMethodReference("Lfixture/Strings;", "A0h", listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            "Ljava/lang/String;")
        val calls = listOf(invoke(Opcode.INVOKE_STATIC, other), invoke(Opcode.INVOKE_STATIC, handlerCall))
        assertEquals(listOf(handlerCall.toString()), buttonHandlerCalls(tap(calls = calls)).map { it.toString() })
        // 582 calls a wrapper of that shape on the handler's singleton.
        assertEquals(listOf(handlerCall.toString()), buttonHandlerCalls(tap(calls = listOf(invoke(Opcode.INVOKE_VIRTUAL, handlerCall))))
            .map { it.toString() })
        // An interface or direct call of that shape isn't one.
        val elsewhere = listOf(invoke(Opcode.INVOKE_INTERFACE, handlerCall), invoke(Opcode.INVOKE_DIRECT, handlerCall))
        assertEquals(emptyList<String>(), buttonHandlerCalls(tap(calls = elsewhere)).map { it.toString() })
        assertEquals(emptyList<String>(), buttonHandlerCalls(tap()).map { it.toString() })
    }

    @Test
    fun `the tap's call reaches the handler it names, or the one its class's wrapper calls`() {
        val owner = handlerCall.definingClass
        val handler = handler(owner = owner)
        assertEquals("A00", buttonHandler(classDef(owner, handler), handlerCall)?.name)

        val wrapperCall = ImmutableMethodReference(owner, "A01", BUTTON_PARAMETERS, "V")
        fun wrapper(vararg calls: Instruction, static: Boolean = false) =
            method("A01", BUTTON_PARAMETERS, 10, calls.toList() + returnVoid, static, owner = owner)
        assertEquals("A00", buttonHandler(classDef(owner, handler, wrapper(invoke(Opcode.INVOKE_STATIC, handlerCall))), wrapperCall)?.name)
        // A wrapper that calls no handler, a static method that isn't one, or a handler in another class reaches none.
        assertEquals(null, buttonHandler(classDef(owner, handler, wrapper()), wrapperCall))
        assertEquals(null, buttonHandler(classDef(owner, handler, wrapper(invoke(Opcode.INVOKE_STATIC, handlerCall), static = true)), wrapperCall))
        val foreign = ImmutableMethodReference("Lfixture/Other;", "A00", BUTTON_PARAMETERS, "V")
        assertEquals(null, buttonHandler(classDef(owner, handler, wrapper(invoke(Opcode.INVOKE_STATIC, foreign))), wrapperCall))
        // Nor does a call to a method the class doesn't declare.
        assertEquals(null, buttonHandler(classDef(owner), handlerCall))

        // 583's wrapper hands a handler of the longer shape, which logs through its helper.
        val handler583 = handler583(owner = owner, call = ImmutableMethodReference(owner, "A00", logHelperCall.parameterTypes,
            "Ljava/lang/String;"))
        val handler583Call = ImmutableMethodReference(owner, "A01", shape583, "V")
        val helper = method("A00", logHelperCall.parameterTypes.map { it.toString() }, 14,
            listOf(string(LONG_PRESS), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), returnType = "Ljava/lang/String;", owner = owner)
        val wrapper583 = method("A02", BUTTON_PARAMETERS, 14,
            listOf(ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, 6, handler583Call), returnVoid), static = false, owner = owner)
        assertEquals("A01", buttonHandler(classDef(owner, helper, handler583, wrapper583),
            ImmutableMethodReference(owner, "A02", BUTTON_PARAMETERS, "V"))?.name)
    }

    private fun MutableMethod.at(index: Int) = implementation!!.instructions.elementAt(index)

    /**
     * What the hook puts first in [method]: the context and the long-press flag copied down into
     * v0 and v1, the question, and a return while Messenger opened. [context] and [flag] are the
     * registers the two parameters sit in.
     */
    private fun assertHookFirst(method: MutableMethod, own: Int, context: Int, flag: Int) {
        assertEquals(Opcode.MOVE_OBJECT_FROM16, method.at(0).opcode)
        assertEquals(0, (method.at(0) as TwoRegisterInstruction).registerA)
        assertEquals("the hook doesn't hand over the context", context, (method.at(0) as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.MOVE_FROM16, method.at(1).opcode)
        assertEquals(1, (method.at(1) as TwoRegisterInstruction).registerA)
        assertEquals("the hook doesn't hand over the long press", flag, (method.at(1) as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.INVOKE_STATIC, method.at(2).opcode)
        val hook = (method.at(2) as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/facebook/chats/MessengerIcon;", hook.definingClass)
        assertEquals("open", hook.name)
        assertEquals(listOf(contextType, "Z"), hook.parameterTypes.map { it.toString() })
        assertEquals("Z", hook.returnType)
        val call = method.at(2) as FiveRegisterInstruction
        assertEquals(listOf(0, 1), listOf(call.registerC, call.registerD).take(call.registerCount))
        assertEquals(Opcode.MOVE_RESULT, method.at(3).opcode)
        assertEquals(0, (method.at(3) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, method.at(4).opcode)
        assertEquals(Opcode.RETURN_VOID, method.at(5).opcode)
        // The branch lands on the method's own first instruction, so none of it is skipped.
        assertEquals(own + 6, method.implementation!!.instructions.count())
        assertEquals(Opcode.CONST_STRING, method.at(6).opcode)
        assertEquals(6, offsetTarget(method, 4))
    }

    @Test
    fun `the tap asks first with its context and its long-press flag and runs as its own otherwise`() {
        // 18 registers, 10 parameters: p0, the context, is v8 and p8, the long press, is v16.
        val tap = MutableMethod(tap())
        val own = tap.implementation!!.instructions.count()
        tap.openMessengerFirst(TAP_LONG_PRESS)
        assertHookFirst(tap, own, context = 8, flag = 16)
    }

    @Test
    fun `the button handler asks first with its context and its long-press flag`() {
        // 25 registers, 5 parameters: p0 is v20 and p3, the long press, is v23.
        val handler = MutableMethod(handler())
        val own = handler.implementation!!.instructions.count()
        handler.openMessengerFirst(buttonLongPress(handler))
        assertHookFirst(handler, own, context = 20, flag = 23)

        // 583: 30 registers, 6 parameters: p0 is v24 and p4, the long press, is v28.
        val handler583 = MutableMethod(method("A01", shape583, 30, listOf(string(LONG_PRESS), returnVoid)))
        val own583 = handler583.implementation!!.instructions.count()
        handler583.openMessengerFirst(buttonLongPress(handler583))
        assertHookFirst(handler583, own583, context = 24, flag = 28)
    }

    /** A method with fewer than two locals to borrow is refused by name, before anything goes in. */
    @Test
    fun `a method without two locals to borrow stops the patch`() {
        val tight = MutableMethod(tap(registers = 11))
        val refusal = assertThrows(PatchException::class.java) { tight.openMessengerFirst(TAP_LONG_PRESS) }
        assertTrue(refusal.message, refusal.message!!.contains(ICON_PATCH))
        assertEquals(Opcode.CONST_STRING, tight.at(0).opcode)
        assertEquals(3, tight.implementation!!.instructions.count())
    }

    /** The hook reads a context and a boolean; any other parameter there is a shape it doesn't know. */
    @Test
    fun `a parameter that isn't the long-press flag stops the patch`() {
        val surface = MutableMethod(tap())
        val refusal = assertThrows(PatchException::class.java) { surface.openMessengerFirst(6) }
        assertTrue(refusal.message, refusal.message!!.contains(ICON_PATCH))
        val noContext = MutableMethod(tap(parameters = listOf(sessionType, contextType) + tapParameters.drop(2)))
        assertThrows(PatchException::class.java) { noContext.openMessengerFirst(TAP_LONG_PRESS) }
        assertEquals(3, surface.implementation!!.instructions.count())
        assertEquals(3, noContext.implementation!!.instructions.count())
    }

    /** Facebook's screen touch dispatch: this in v1 and the event in v2, answering false. */
    private fun dispatch(): Method = ImmutableMethod(
        FRAGMENT_ACTIVITY, "dispatchTouchEvent", listOf(ImmutableMethodParameter(MOTION_EVENT, null, null)), "Z",
        AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(3, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)),
            null, null),
    )

    private fun classDef(type: String, vararg methods: Method) =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.map(ImmutableMethod::of))

    /**
     * A build with the icon's tap calling the button handler, FbFragmentActivity declaring
     * [screen], and the places Leave calls and chats to Messenger goes first in, which the patch
     * finds before it changes anything.
     */
    private fun build(vararg screen: Method) = PatchContexts.of(
        listOf(
            classDef(TAP_CLASS, tap(calls = listOf(invoke(Opcode.INVOKE_STATIC, handlerCall)))),
            classDef(handlerCall.definingClass, handler(owner = handlerCall.definingClass)),
            classDef(FRAGMENT_ACTIVITY, *screen),
            ExtensionDex.classDef(SETTINGS_STATUS),
            ExtensionDex.classDef(HANDOFF),
        ) + HandoffShapes.classes(),
    )

    /** The touch, in the event's own register through the range form, then the dispatch's own code. */
    private fun assertTouchFirst(dispatch: MutableMethod) {
        val call = dispatch.at(0)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("Lapp/morphe/extension/facebook/chats/MessengerIcon;->touch(Landroid/view/MotionEvent;)V",
            (call as ReferenceInstruction).reference.toString())
        assertEquals("the hook doesn't hand over the event", listOf(2, 1),
            (call as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals(3, dispatch.implementation!!.instructions.count())
        assertEquals(Opcode.CONST_4, dispatch.at(1).opcode)
    }

    @Test
    fun `every touch on a screen goes to the extension first and on to Facebook unchanged`() {
        val dispatch = MutableMethod(dispatch())
        dispatch.handTouchesToIcon()
        assertTouchFirst(dispatch)
    }

    /**
     * The patch puts all three hooks in: the tap and the handler ask first, and every touch goes to
     * the extension, which is how a held press that reaches the tap as a plain tap stays Facebook's.
     */
    @Test
    fun `the patch asks first in the tap and the handler and hands the extension every touch`() {
        val context = build(dispatch())
        openMessengerFromTopBarPatch.execute(context)
        val tap = context.mutableClassDefBy("Lfixture/MessengerIcon;").methods.single()
        assertEquals("open", ((tap.at(2) as ReferenceInstruction).reference as MethodReference).name)
        val handler = context.mutableClassDefBy(handlerCall.definingClass).methods.single()
        assertEquals("open", ((handler.at(2) as ReferenceInstruction).reference as MethodReference).name)
        assertTouchFirst(context.mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single())
    }

    /** 582's route: the tap calls a wrapper on the handler's singleton, and the handler behind it asks first. */
    @Test
    fun `through the singleton's wrapper the handler asks first and the wrapper is left alone`() {
        val owner = handlerCall.definingClass
        val wrapperCall = ImmutableMethodReference(owner, "A01", BUTTON_PARAMETERS, "V")
        val wrapper = method("A01", BUTTON_PARAMETERS, 10, listOf(invoke(Opcode.INVOKE_STATIC, handlerCall), returnVoid),
            static = false, owner = owner)
        val context = PatchContexts.of(
            listOf(
                classDef(TAP_CLASS, tap(calls = listOf(invoke(Opcode.INVOKE_VIRTUAL, wrapperCall)))),
                classDef(owner, handler(owner = owner), wrapper),
                classDef(FRAGMENT_ACTIVITY, dispatch()),
                ExtensionDex.classDef(SETTINGS_STATUS),
                ExtensionDex.classDef(HANDOFF),
            ) + HandoffShapes.classes(),
        )
        openMessengerFromTopBarPatch.execute(context)
        val methods = context.mutableClassDefBy(owner).methods
        assertEquals("open", ((methods.single { it.name == "A00" }.at(2) as ReferenceInstruction).reference as MethodReference).name)
        assertEquals(2, methods.single { it.name == "A01" }.implementation!!.instructions.count())
    }

    /**
     * Without the screen's touch dispatch the patch can't tell a held press from a tap, so it stops
     * before any hook goes in: a half-applied patch would send long presses to Messenger.
     */
    @Test
    fun `a build without the screen's touch dispatch stops the patch before anything goes in`() {
        val context = build()
        val refusal = assertThrows(PatchException::class.java) { openMessengerFromTopBarPatch.execute(context) }
        assertTrue(refusal.message, refusal.message!!.contains(ICON_PATCH))
        assertTrue(refusal.message, refusal.message!!.contains("dispatchTouchEvent"))
        val tap = context.mutableClassDefBy("Lfixture/MessengerIcon;").methods.single()
        assertEquals(Opcode.CONST_STRING, tap.at(0).opcode)
        val handler = context.mutableClassDefBy(handlerCall.definingClass).methods.single()
        assertEquals(Opcode.CONST_STRING, handler.at(0).opcode)
    }

    /** The instruction index a branch at [index] lands on. */
    private fun offsetTarget(method: MutableMethod, index: Int): Int {
        val instructions = method.implementation!!.instructions.toList()
        val branch = instructions[index] as OffsetInstruction
        var address = 0
        val addresses = instructions.map { val at = address; address += it.codeUnits; at }
        return addresses.indexOf(addresses[index] + branch.codeOffset)
    }

    companion object {
        /** The made-up class holding the icon's tap. */
        const val TAP_CLASS = "Lfixture/MessengerIcon;"

        /**
         * The icon's tap calling the button handler, and FbFragmentActivity with its touch dispatch:
         * the rest of the patch, for the Leave calls and chats to Messenger tests.
         */
        fun iconClasses(): List<ClassDef> = MessengerIconShapesTest().run {
            listOf(
                classDef(TAP_CLASS, tap(calls = listOf(invoke(Opcode.INVOKE_STATIC, handlerCall)))),
                classDef(handlerCall.definingClass, handler(owner = handlerCall.definingClass)),
                classDef(FRAGMENT_ACTIVITY, dispatch()),
            )
        }
    }
}
