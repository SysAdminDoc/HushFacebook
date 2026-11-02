/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.notifications.TRAY_MANAGER
import app.morphe.patches.facebook.notifications.isPostMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchors of Open Messenger from the top bar on every declared Facebook build: one tap of the
 * top bar's Messenger icon, with the click listener handing it 0 and the long-click listener 1 in
 * the parameter the hook reads as the long press; one Messenger button handler it calls, which
 * gets that same flag in its own long-press parameter and logs "long_press" only when it's set
 * (from 583 through a helper of its class it hands the flag to); and two locals to borrow in
 * each. Nothing else holds both entry points, and no other static method of a handler's shape
 * logs "long_press", which is what the mutation contracts pick the two by. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class MessengerIconFixtureTest {
    private val openCall = "Lapp/morphe/extension/facebook/chats/MessengerIcon;->open(Landroid/content/Context;Z)Z"
    private val touchCall = "Lapp/morphe/extension/facebook/chats/MessengerIcon;->touch(Landroid/view/MotionEvent;)V"

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun key(method: MethodReference) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun calls(method: Method, target: Method): Boolean =
        method.implementation?.instructions?.any { it.call?.let(::key) == key(target) } == true

    private fun locals(method: Method): Int {
        val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        return method.implementation!!.registerCount - self - method.parameterTypes.sumOf {
            if (it.toString() == "J" || it.toString() == "D") 2 else 1
        }
    }

    /** The register holding argument [index] of the call [call], all of whose arguments are narrow. */
    private fun argument(call: Instruction, index: Int): Int = when (call) {
        is RegisterRangeInstruction -> call.startRegister + index
        is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)[index]
        else -> throw AssertionError("not a call: $call")
    }

    /** The last instruction before [at] in [code] that writes [register], or null. */
    private fun lastWrite(code: List<Instruction>, at: Int, register: Int): Instruction? =
        code.subList(0, at).lastOrNull {
            it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
        }

    /**
     * Where [register], as read at [at] in [code], came from: back through plain moves to the move
     * whose source nothing before it wrote. Redex reuses parameter registers as locals (582's tap
     * moves v23 into v15 first), so a move's source is followed to wherever it was last written.
     */
    private fun handedFrom(code: List<Instruction>, at: Int, register: Int): Instruction? {
        var before = at
        var handed = lastWrite(code, before, register)
        while (handed is TwoRegisterInstruction && handed.opcode.name.startsWith("move")) {
            val move: Instruction = handed
            val source = handed.registerB
            before = code.subList(0, before).indexOfLast { it === move }
            handed = lastWrite(code, before, source) ?: break
        }
        return handed
    }

    /** The callers of [target] anywhere in [bundle]. */
    private fun callers(bundle: java.io.File, target: Method): List<Method> =
        FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
            dex.methodSection.any { it.definingClass == target.definingClass && it.name == target.name }
        }) { calls(it, target) }

    /** The registers a call passes, in order. */
    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    /**
     * The patch itself, run on each build's own tap, handler and screen classes and the classes
     * Leave calls and chats to Messenger hooks. The tap and the handler ask the extension first,
     * every touch on a Facebook screen goes to the extension before Facebook sees it, the MQTT
     * connection gives the extension its call capability, and the call pushes, the ring parse and
     * the chat dispatcher ask before Facebook rings or notifies. Where the icon's long-click listener is off (MobileConfig builds it
     * behind a flag, the same flag that gives the top bar's touch listener its long-press detector),
     * a press held on the icon reaches the tap as a plain click when the finger lifts, with 0 for
     * the long press, and only the touch tells the extension it was held.
     */
    @Test
    fun `on each declared build the patch asks first in the tap and the handler and sees every touch`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val tapClass = FixtureDex.classesHolding(bundle, REELS_TAB_ENTRY).single { owner -> owner.methods.any(::isIconTap) }
                val tap = tapClass.methods.single(::isIconTap)
                val handlerType = buttonHandlerCalls(tap).map { it.definingClass }.distinct().single()
                val classes = FixtureDex.classes(bundle, setOf(handlerType, FRAGMENT_ACTIVITY))
                val handoffClasses = HandoffFixture.classes(bundle)
                val context = PatchContexts.of(
                    (listOf(tapClass, classes.getValue(handlerType), classes.getValue(FRAGMENT_ACTIVITY),
                        ExtensionDex.classDef(SETTINGS_STATUS), ExtensionDex.classDef(HANDOFF)) + handoffClasses).distinctBy { it.type },
                )
                openMessengerFromTopBarPatch.execute(context)

                for (hooked in listOf(tapClass.type, handlerType)) {
                    val asks = context.mutableClassDefBy(hooked).methods.filter { method ->
                        method.implementation?.instructions?.take(3)?.any { it.call?.toString() == openCall } == true
                    }
                    assertEquals("$name: methods of $hooked asking the extension first", 1, asks.size)
                }
                val dispatch = touchDispatches(context.mutableClassDefBy(FRAGMENT_ACTIVITY)).single()
                val code = dispatch.implementation!!.instructions.toList()
                val first = code.indexOfFirst { it.call?.toString() == touchCall }
                assertEquals("$name: dispatchTouchEvent doesn't hand the touch to the extension first", 0, first)
                assertEquals("$name: the touch the extension gets", listOf(dispatch.localRegisterCount() + 1),
                    code[first].registers())
                assertTrue("$name: dispatchTouchEvent no longer hands the event on",
                    code.drop(1).any { it.call?.name == "dispatchTouchEvent" })

                // Leave calls and chats to Messenger: both VOIP handlers ask the extension before
                // they take a call push, the ring parse before it takes its lock, and the chat
                // dispatcher, once the notification is in its local, before the notifiers see it,
                // and the push tray for the chats Facebook posts itself.
                fun firstCall(method: Method, wanted: String) =
                    method.implementation!!.instructions.indexOfFirst { it.call?.toString() == wanted }
                val mqttTarget = mqttCapabilitiesTarget(
                    handoffClasses.single { it.type == MQTT_XPLAT_SERVICE_DELEGATE },
                )!!
                val mqtt = context.mutableClassDefBy(MQTT_XPLAT_SERVICE_DELEGATE).methods.single {
                    it.name == mqttTarget.method.name && it.returnType == mqttTarget.method.returnType
                }
                assertEquals("$name: MQTT capabilities aren't changed at their construction",
                    mqttTarget.constructionIndex, firstCall(mqtt, CLIENT_CAPABILITIES))
                val voip = voipConstant(handoffClasses.single { it.type == NOTIFICATION_TYPE })!!
                val handles = handoffClasses.filter { claimsVoip(it, voip) }.map { handler ->
                    context.mutableClassDefBy(handler.type).methods.single(::isPushHandle)
                }
                assertEquals("$name: VOIP handlers", 2, handles.size)
                for (handle in handles) {
                    assertEquals("$name: ${handle.definingClass} doesn't ask before taking a call push", 0,
                        firstCall(handle, DROP_CALL_PUSH))
                }
                val parse = context.mutableClassDefBy(handoffClasses.single(::isRingParser).type).methods.single(::isRingParse)
                assertEquals("$name: the ring parse doesn't ask first", 0, firstCall(parse, DROP_RING))
                assertEquals("$name: the ring parse's lock moved", Opcode.MONITOR_ENTER,
                    parse.implementation!!.instructions.toList()[6].opcode)
                val chatDispatch = context.mutableClassDefBy(MESSAGES_NOTIFICATION_MANAGER).methods.single(::isChatDispatch)
                assertEquals("$name: the chat dispatcher doesn't ask first", 1, firstCall(chatDispatch, DROP_CHAT))
                // The push tray, once the push's type is read out of the builder, before Facebook posts.
                val trayPost = context.mutableClassDefBy(TRAY_MANAGER).methods.single(::isPostMethod)
                assertEquals("$name: the push tray doesn't ask first", 5, firstCall(trayPost, DROP_CHAT_PUSH))
                // Every handoff anchor was found, so the settings page offers the switch (#39).
                val inBuild = context.mutableClassDefBy(HANDOFF).methods.single { it.name == "inBuild" }
                assertEquals("$name: MessengerHandoff.inBuild still answers false", 1,
                    (inBuild.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `each declared build has one Messenger icon tap and one button handler, both handed the long press`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, REELS_TAB_ENTRY)
                    .flatMap { it.methods }.filter { holdsString(it, REELS_TAB_ENTRY) && holdsString(it, NAVBAR_ENTRY) }
                assertEquals("$name: methods holding both entry points", 1, holders.size)
                val taps = FixtureDex.classesHolding(bundle, REELS_TAB_ENTRY).flatMap { it.methods }.filter(::isIconTap)
                assertEquals("$name: Messenger icon taps", 1, taps.size)
                val tap = taps.single()
                assertTrue("$name: the tap has fewer than two locals", locals(tap) >= 2)

                // The icon's click listener hands the tap 0 in the long-press parameter, and its
                // long-click listener 1.
                val flags = callers(bundle, tap).associate { caller ->
                    val code = caller.implementation!!.instructions.toList()
                    val at = code.indexOfFirst { it.call?.let(::key) == key(tap) }
                    val write = lastWrite(code, at, argument(code[at], TAP_LONG_PRESS))
                    assertEquals("$name: ${caller.definingClass}->${caller.name} hands the tap a long press it didn't set",
                        Opcode.CONST_4, write?.opcode)
                    caller.name + caller.parameterTypes.joinToString("", "(", ")") + caller.returnType to
                        (write as NarrowLiteralInstruction).narrowLiteral
                }
                assertEquals("$name: the tap's callers and the long press each hands it",
                    mapOf("onClick(Landroid/view/View;)V" to 0, "onLongClick(Landroid/view/View;)Z" to 1), flags)

                // The long-click listener is built in one place, only when a flag the builder is
                // handed says so: its new-instance sits right behind an if-eqz on a parameter. With
                // the flag off the icon has no long-click listener, and a held press reaches the
                // tap through the click listener with 0, which is why the patch reads the touches.
                val longClick = callers(bundle, tap).single { it.name == "onLongClick" }.definingClass
                fun buildsLongClick(instruction: Instruction) = instruction.opcode == Opcode.NEW_INSTANCE &&
                    (instruction as ReferenceInstruction).reference.toString() == longClick
                val builders = FixtureDex.methodsWhere(bundle, dexFilter = { dex -> dex.typeSection.any { it == longClick } }) {
                    it.implementation?.instructions?.any(::buildsLongClick) == true
                }
                assertEquals("$name: methods building the long-click listener $longClick", 1, builders.size)
                val builderCode = builders.single().implementation!!.instructions.toList()
                val built = builderCode.indexOfFirst(::buildsLongClick)
                val guard = builderCode[built - 1]
                assertTrue("$name: the long-click listener is built without a flag ahead of it ($guard)",
                    guard.opcode == Opcode.IF_EQZ && (guard as OneRegisterInstruction).registerA >= locals(builders.single()))

                // The tap calls one button handler (582 and 583 through a wrapper on the handler's
                // singleton), and hands it its own long-press parameter.
                val handlerCalls = buttonHandlerCalls(tap).distinctBy(::key)
                assertEquals("$name: button handlers the tap calls", 1, handlerCalls.size)
                val owner = FixtureDex.classes(bundle, setOf(handlerCalls.single().definingClass)).values.single()
                val handler = buttonHandler(owner, handlerCalls.single())
                    ?: throw AssertionError("$name: ${key(handlerCalls.single())} doesn't reach a button handler in its class")
                assertTrue("$name: ${key(handler)} isn't the button handler", isButtonHandler(handler, owner))
                assertTrue("$name: the button handler has fewer than two locals", locals(handler) >= 2)
                val tapCode = tap.implementation!!.instructions.toList()
                val at = tapCode.indexOfFirst { it.call?.let(::key) == key(handlerCalls.single()) }
                val receiver = if (tapCode[at].opcode.name.startsWith("invoke-static")) 0 else 1
                val handed = handedFrom(tapCode, at, argument(tapCode[at], buttonLongPress(handlerCalls.single()) + receiver))
                val tapFlag = locals(tap) + TAP_LONG_PRESS
                assertTrue("$name: the tap hands the handler $handed, not its long press v$tapFlag",
                    handed is TwoRegisterInstruction && handed.opcode.name.startsWith("move") && handed.registerB == tapFlag)

                // "long_press" is logged only behind a branch on the long press: in the handler up
                // to 582, and from 583 in a helper the handler hands its long press to.
                val log = longPressLog(handler, owner)
                    ?: throw AssertionError("$name: ${key(handler)} logs no long press")
                val logFlag = if (log === handler) buttonLongPress(handler) else {
                    val flags = log.parameterTypes.indices.filter { log.parameterTypes[it].toString() == "Z" }
                    assertEquals("$name: ${key(log)}'s flags", 1, flags.size)
                    val handlerCode = handler.implementation!!.instructions.toList()
                    val logCall = handlerCode.indexOfFirst { it.call?.let(::key) == key(log) }
                    val handlerFlag = handler.parameterRegisterNumber(buttonLongPress(handler))
                    val passed = handedFrom(handlerCode, logCall, argument(handlerCode[logCall], flags.single()))
                    assertTrue("$name: the handler hands ${key(log)} $passed, not its long press v$handlerFlag",
                        passed is TwoRegisterInstruction && passed.opcode.name.startsWith("move") && passed.registerB == handlerFlag)
                    flags.single()
                }
                val logCode = log.implementation!!.instructions.toList()
                val logged = logCode.indexOfFirst {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == LONG_PRESS
                }
                val flagRegister = log.parameterRegisterNumber(logFlag)
                assertTrue("$name: ${key(log)} logs a long press without asking v$flagRegister",
                    logCode.subList(0, logged).any {
                        it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == flagRegister
                    })

                // What the contracts pick the handler by: no other static method of a handler's
                // shape logs "long_press" through its class.
                val shaped = FixtureDex.classesHolding(bundle, LONG_PRESS).flatMap { classDef ->
                    classDef.methods.filter { isButtonHandler(it, classDef) }
                }
                assertEquals("$name: static Messenger button handlers logging \"$LONG_PRESS\"",
                    listOf(key(handler)), shaped.map(::key))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
