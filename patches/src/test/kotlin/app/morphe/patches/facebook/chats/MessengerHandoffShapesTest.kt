/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.facebook.notifications.BUILDER_PARAMETER
import app.morphe.patches.facebook.notifications.SHOW_START
import app.morphe.patches.facebook.notifications.TRAY_MANAGER
import app.morphe.patches.facebook.notifications.TRAY_NOTIFICATION
import app.morphe.patches.facebook.notifications.TYPE_FIELD
import app.morphe.patches.facebook.notifications.blockPromotionalNotificationsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A made-up Facebook with each place Leave calls and chats to Messenger goes first in, shaped the
 * way MessengerHandoffAnchors.kt describes 582: NotificationType with its VOIP constant, a push
 * handler claiming VOIP alone, a push handler claiming something else, the rooms notification
 * parser, and MessagesNotificationManager with its dispatcher and a second method of the same shape
 * that calls no notifier. The Open Messenger from the top bar tests run the patch over these too.
 */
internal object HandoffShapes {
    const val HANDLER = "Lfixture/RtcPushHandler;"
    const val OTHER_HANDLER = "Lfixture/MarketplacePushHandler;"
    const val PARSER = "Lfixture/RingParser;"
    const val PUSH = "Lfixture/Push;"
    const val SOURCE = "Lfixture/PushSource;"
    const val LOGGER = "Lfixture/PushLogger;"
    const val RING_ENUM = "Lfixture/RingKind;"
    const val RTC_MESSAGE = "Lfixture/RtcMessage;"
    const val RING = "Lfixture/Ring;"
    const val NOTIFIER = "Lfixture/Notifier;"
    const val STRING = "Ljava/lang/String;"

    val connectionParameters = listOf(
        STRING, STRING, STRING, "I", STRING, STRING, STRING, STRING, STRING, "I", "J", "J", STRING,
    )
    val connectionConstructor = ImmutableMethodReference(
        MQTT_CONNECTION_CONFIG, "<init>", connectionParameters, "V",
    )

    val voip = ImmutableFieldReference(NOTIFICATION_TYPE, "voip", NOTIFICATION_TYPE)
    val marketplace = ImmutableFieldReference(NOTIFICATION_TYPE, "marketplace", NOTIFICATION_TYPE)
    val matches = ImmutableMethodReference(NOTIFICATION_TYPE, "matches", listOf(STRING), "Z")
    val notify = ImmutableMethodReference(NOTIFIER, "notify", listOf(PUSH_SESSION, MESSAGING_NOTIFICATION), "V")
    val handleParameters = listOf(PUSH_CONTEXT, PUSH_SESSION, PUSH_PROPERTY, PUSH, LOGGER, PUSH_INFRA)
    val parseParameters = listOf(PUSH_SESSION, RING_ENUM, RTC_MESSAGE, "Ljava/lang/Long;", "[B")
    val dispatchParameters = listOf(PUSH_SESSION, MESSAGES_NOTIFICATION_MANAGER, MESSAGING_NOTIFICATION)

    fun string(register: Int, value: String) = ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

    val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)

    fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        body: List<Instruction>?,
        static: Boolean = false,
    ): Method = ImmutableMethod(
        owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0),
        null, null, body?.let { ImmutableMethodImplementation(registers, it, null, null) },
    )

    fun classDef(type: String, vararg methods: Method, fields: List<ImmutableField>? = null, superclass: String = "Ljava/lang/Object;") =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, fields, methods.map(ImmutableMethod::of))

    /** NotificationType's static initializer: each name loaded, then its constant stored. */
    fun notificationType(vararg constants: Pair<String, ImmutableFieldReference>): ClassDef {
        val body = constants.flatMap { (name, field) ->
            listOf(string(1, name), ImmutableInstruction21c(Opcode.SPUT_OBJECT, 0, field))
        } + returnVoid
        val fields = constants.map { (_, field) ->
            ImmutableField(NOTIFICATION_TYPE, field.name, NOTIFICATION_TYPE,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value,
                null, null, null)
        }
        return classDef(NOTIFICATION_TYPE, method(NOTIFICATION_TYPE, "<clinit>", emptyList(), "V", 2, body, static = true),
            fields = fields, superclass = "Ljava/lang/Enum;")
    }

    /** A claim over (source, type) reading [constant] and asking NotificationType, as 582's six-instruction claim does. */
    fun claim(owner: String = HANDLER, constant: ImmutableFieldReference = voip, asks: MethodReference = matches,
              parameters: List<String> = listOf(SOURCE, STRING), returnType: String = "Z", static: Boolean = false,
              padding: Int = 0): Method =
        method(owner, "claim", parameters, returnType, 4, listOf(
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, constant),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 3, 0, 0, 0, asks),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
        ) + List(padding) { ImmutableInstruction10x(Opcode.NOP) } + ImmutableInstruction11x(Opcode.RETURN, 0), static)

    /** A handle method with [locals] locals, which logs its name first and returns. */
    fun handle(owner: String = HANDLER, parameters: List<String> = handleParameters, locals: Int = 1,
               returnType: String = "V", static: Boolean = false): Method =
        method(owner, "handle", parameters, returnType, locals + (if (static) 0 else 1) + parameters.size,
            listOf(string(0, "RtcPushHandler.onNotification"), returnVoid), static)

    /** The parser's parse: 582's takes the lock on itself as its second instruction. */
    fun parse(owner: String = PARSER, parameters: List<String> = parseParameters, returnType: String = RING,
              static: Boolean = false, locals: Int = 2): Method {
        val self = locals
        return method(owner, "parse", parameters, returnType, locals + 1 + parameters.size, listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction11x(Opcode.MONITOR_ENTER, self),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.MONITOR_EXIT, self),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ), static)
    }

    fun ringType(owner: String = PARSER) =
        method(owner, "getRingType\$fbandroid_java_com_facebook_rooms_mainapp_notifications_parser_parser",
            listOf(RTC_MESSAGE), "I", 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 4), ImmutableInstruction11x(Opcode.RETURN, 0)))

    fun payload(owner: String = PARSER) =
        method(owner, "getBinaryPayloadFromPushNotif\$fbandroid_java_com_facebook_rooms_mainapp_notifications_parser_parser",
            listOf(PUSH), "[B", 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)))

    /** A static of the dispatcher's shape, handing the notification to the notifiers through [call] when set. */
    fun dispatch(name: String = "dispatch", call: Opcode? = Opcode.INVOKE_INTERFACE, static: Boolean = true,
                 parameters: List<String> = dispatchParameters, locals: Int = 2): Method {
        val first = locals
        return method(MESSAGES_NOTIFICATION_MANAGER, name, parameters, "V", locals + (if (static) 0 else 1) + parameters.size,
            listOfNotNull(
                string(0, "MessagesNotificationManager.$name"),
                // The notifier in v1, then the session and the notification from their parameters.
                call?.let {
                    if (it == Opcode.INVOKE_INTERFACE_RANGE) ImmutableInstruction3rc(it, first, 3, notify)
                    else ImmutableInstruction35c(it, 3, 1, first, first + 2, 0, 0, notify)
                },
                returnVoid,
            ), static)
    }

    /** The kept MQTT delegate's provider, with capabilities as the constructor's first long. */
    fun mqttConfig(constructions: Int = 1, parameters: List<String> = listOf("Z"),
                   returnType: String = MQTT_CONNECTION_CONFIG, static: Boolean = false): Method {
        val body = mutableListOf<Instruction>(
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(MQTT_CONNECTION_CONFIG)),
            ImmutableInstruction21s(Opcode.CONST_WIDE_16, 11, 0x217),
        )
        repeat(constructions) {
            body += ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 0, 16, connectionConstructor)
        }
        body += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        return method(MQTT_XPLAT_SERVICE_DELEGATE, "connection", parameters, returnType, 18, body, static)
    }

    const val TRAY_BUILDER = "Lfixture/NotificationBuilder;"

    val postParameters = listOf(
        "Landroid/content/Intent;", "Lcom/facebook/notifications/logging/NotificationLogObject;",
        "Lcom/facebook/notifications/logging/NotificationsLogger\$Component;", TRAY_BUILDER, "I",
    )

    /** The push tray's post method, with one local: it loads show_notif_start and returns. */
    fun post(): Method = method(TRAY_MANAGER, "post", postParameters, "V", 2 + postParameters.size,
        listOf(string(0, SHOW_START), returnVoid))

    private fun instanceField(owner: String, name: String, type: String) =
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

    /** The builder the post method takes, holding the push, and the push with its type. */
    val trayBuilder = classDef(TRAY_BUILDER, fields = listOf(instanceField(TRAY_BUILDER, "push", TRAY_NOTIFICATION)))
    val trayNotification = classDef(TRAY_NOTIFICATION, fields = listOf(instanceField(TRAY_NOTIFICATION, TYPE_FIELD, STRING)))

    /** Every class of the made-up Facebook, [leaveOut] left out and [extra] added. */
    fun classes(leaveOut: Set<String> = emptySet(), extra: List<ClassDef> = emptyList()): List<ClassDef> =
        listOf(
            notificationType(VOIP to voip, "MARKETPLACE" to marketplace),
            classDef(HANDLER, claim(), handle()),
            classDef(OTHER_HANDLER, claim(OTHER_HANDLER, constant = marketplace), handle(OTHER_HANDLER)),
            classDef(PARSER, ringType(), payload(), parse()),
            classDef(MESSAGES_NOTIFICATION_MANAGER, dispatch(), dispatch("plain", call = null)),
            classDef(MQTT_XPLAT_SERVICE_DELEGATE, mqttConfig()),
            classDef(TRAY_MANAGER, post()),
            trayBuilder,
            trayNotification,
        ).filter { it.type !in leaveOut } + extra
}

/**
 * The parts of Leave calls and chats to Messenger that need no Facebook build: which methods and
 * fields the anchors take and turn down, the code each hook puts first, and the patch stopping
 * before anything goes in when a build lacks one.
 */
class MessengerHandoffShapesTest {
    private val shapes = HandoffShapes

    @Test
    fun `the VOIP constant is the field NotificationType's initializer stores right after loading the name`() {
        assertEquals(shapes.voip.name, voipConstant(shapes.notificationType(VOIP to shapes.voip, "MARKETPLACE" to shapes.marketplace))?.name)
        assertEquals(shapes.voip.name, voipConstant(shapes.notificationType("MARKETPLACE" to shapes.marketplace, VOIP to shapes.voip))?.name)
        assertNull(voipConstant(shapes.notificationType("MARKETPLACE" to shapes.marketplace)))
        // A field of another type isn't a constant of the enum, and another name loaded first means
        // the store isn't VOIP's.
        val elsewhere = ImmutableFieldReference("Lfixture/Other;", "voip", NOTIFICATION_TYPE)
        assertNull(voipConstant(shapes.notificationType(VOIP to elsewhere)))
        val crossed = shapes.classDef(NOTIFICATION_TYPE, shapes.method(NOTIFICATION_TYPE, "<clinit>", emptyList(), "V", 2, listOf(
            shapes.string(1, VOIP), shapes.string(1, "MARKETPLACE"),
            ImmutableInstruction21c(Opcode.SPUT_OBJECT, 0, shapes.marketplace), shapes.returnVoid), static = true))
        assertNull(voipConstant(crossed))
    }

    @Test
    fun `a VOIP claim reads the constant and asks NotificationType in a few instructions`() {
        assertTrue(isVoipClaim(shapes.claim(), shapes.voip))
        assertFalse("another type's claim", isVoipClaim(shapes.claim(constant = shapes.marketplace), shapes.voip))
        val elsewhere = ImmutableMethodReference("Lfixture/TypeSet;", "contains", listOf(HandoffShapes.STRING), "Z")
        assertFalse("a claim that asks a set", isVoipClaim(shapes.claim(asks = elsewhere), shapes.voip))
        assertFalse(isVoipClaim(shapes.claim(static = true), shapes.voip))
        assertFalse(isVoipClaim(shapes.claim(returnType = "V"), shapes.voip))
        assertFalse(isVoipClaim(shapes.claim(parameters = listOf(HandoffShapes.STRING, HandoffShapes.SOURCE)), shapes.voip))
        assertFalse(isVoipClaim(shapes.claim(parameters = listOf(HandoffShapes.SOURCE)), shapes.voip))
        // Up to CLAIM_SIZE instructions is still a claim of one type; past it, a handler that claims
        // many and reads VOIP among them.
        assertTrue(isVoipClaim(shapes.claim(padding = CLAIM_SIZE - 4), shapes.voip))
        assertFalse(isVoipClaim(shapes.claim(padding = CLAIM_SIZE - 3), shapes.voip))
    }

    @Test
    fun `the handle method is the instance void over Context, FbUserSession, PushProperty, the push, a helper and PushInfraMetaData`() {
        assertTrue(isPushHandle(shapes.handle()))
        assertFalse(isPushHandle(shapes.handle(static = true)))
        assertFalse(isPushHandle(shapes.handle(returnType = "Z")))
        assertFalse(isPushHandle(shapes.handle(parameters = shapes.handleParameters.dropLast(1))))
        assertFalse(isPushHandle(shapes.handle(parameters = shapes.handleParameters.reversed())))
        assertFalse(isPushHandle(shapes.handle(parameters = shapes.handleParameters.map { if (it == HandoffShapes.PUSH) "I" else it })))
        assertTrue(claimsVoip(shapes.classDef(HandoffShapes.HANDLER, shapes.claim(), shapes.handle()), shapes.voip))
        assertFalse(claimsVoip(shapes.classDef(HandoffShapes.HANDLER, shapes.handle()), shapes.voip))
    }

    @Test
    fun `the parser declares both kept Kotlin names and its parse answers an object over the ring's parameters`() {
        assertTrue(isRingParser(shapes.classDef(HandoffShapes.PARSER, shapes.ringType(), shapes.payload(), shapes.parse())))
        assertFalse(isRingParser(shapes.classDef(HandoffShapes.PARSER, shapes.ringType(), shapes.parse())))
        assertFalse(isRingParser(shapes.classDef(HandoffShapes.PARSER, shapes.payload(), shapes.parse())))
        assertTrue(isRingParse(shapes.parse()))
        assertFalse(isRingParse(shapes.parse(static = true)))
        assertFalse(isRingParse(shapes.parse(returnType = "V")))
        assertFalse(isRingParse(shapes.parse(returnType = "Z")))
        assertFalse(isRingParse(shapes.parse(parameters = shapes.parseParameters.dropLast(1))))
        assertFalse(isRingParse(shapes.parse(parameters = shapes.parseParameters.map { if (it == "Ljava/lang/Long;") "J" else it })))
    }

    @Test
    fun `the chat dispatcher is the static method that hands the notification to a notifier`() {
        assertTrue(isChatDispatch(shapes.dispatch()))
        assertTrue(isChatDispatch(shapes.dispatch(call = Opcode.INVOKE_INTERFACE_RANGE)))
        assertFalse("the other method of its shape calls no notifier", isChatDispatch(shapes.dispatch(call = null)))
        assertFalse(isChatDispatch(shapes.dispatch(call = Opcode.INVOKE_VIRTUAL)))
        assertFalse(isChatDispatch(shapes.dispatch(static = false)))
        assertFalse(isChatDispatch(shapes.dispatch(parameters = shapes.dispatchParameters.reversed())))
    }

    @Test
    fun `the MQTT target is the kept provider's one ConnectionConfig construction and its first long`() {
        val target = mqttCapabilitiesTarget(shapes.classDef(MQTT_XPLAT_SERVICE_DELEGATE, shapes.mqttConfig()))!!
        assertEquals("connection", target.method.name)
        assertEquals(2, target.constructionIndex)
        assertEquals(11, target.capabilitiesRegister)
        assertNull(mqttCapabilitiesTarget(shapes.classDef(MQTT_XPLAT_SERVICE_DELEGATE,
            shapes.mqttConfig(parameters = emptyList()))))
        assertNull(mqttCapabilitiesTarget(shapes.classDef(MQTT_XPLAT_SERVICE_DELEGATE,
            shapes.mqttConfig(returnType = "Ljava/lang/Object;"))))
        assertNull(mqttCapabilitiesTarget(shapes.classDef(MQTT_XPLAT_SERVICE_DELEGATE,
            shapes.mqttConfig(constructions = 2))))
    }

    private fun MutableMethod.at(index: Int) = implementation!!.instructions.elementAt(index)

    private fun MutableMethod.call(index: Int) = (at(index) as ReferenceInstruction).reference as MethodReference

    /** The instruction index a branch at [index] lands on. */
    private fun offsetTarget(method: MutableMethod, index: Int): Int {
        val instructions = method.implementation!!.instructions.toList()
        val branch = instructions[index] as OffsetInstruction
        var address = 0
        val addresses = instructions.map { val at = address; address += it.codeUnits; at }
        return addresses.indexOf(addresses[index] + branch.codeOffset)
    }

    private fun assertAsks(method: MutableMethod, index: Int, name: String, parameters: List<String>, registers: List<Int>) {
        assertEquals(Opcode.INVOKE_STATIC, method.at(index).opcode)
        val call = method.call(index)
        assertEquals("Lapp/morphe/extension/facebook/chats/MessengerHandoff;", call.definingClass)
        assertEquals(name, call.name)
        assertEquals(parameters, call.parameterTypes.map { it.toString() })
        assertEquals("Z", call.returnType)
        val invoke = method.at(index) as Instruction35c
        assertEquals(registers, listOf(invoke.registerC, invoke.registerD).take(invoke.registerCount))
        assertEquals(Opcode.MOVE_RESULT, method.at(index + 1).opcode)
        assertEquals(0, (method.at(index + 1) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, method.at(index + 2).opcode)
        assertEquals(0, (method.at(index + 2) as OneRegisterInstruction).registerA)
    }

    @Test
    fun `a call push handler returns first while Messenger takes calls and runs as its own otherwise`() {
        val handle = MutableMethod(shapes.handle())
        val own = handle.implementation!!.instructions.count()
        handle.dropCallPushFirst()
        assertEquals(own + 4, handle.implementation!!.instructions.count())
        assertAsks(handle, 0, "dropCallPush", emptyList(), emptyList())
        assertEquals(Opcode.RETURN_VOID, handle.at(3).opcode)
        // No: the handler's own first instruction, so none of it is skipped.
        assertEquals(4, offsetTarget(handle, 2))
        assertEquals(Opcode.CONST_STRING, handle.at(4).opcode)
    }

    @Test
    fun `the ring parse answers null before it takes its lock while Messenger takes calls`() {
        val parse = MutableMethod(shapes.parse())
        val own = parse.implementation!!.instructions.count()
        parse.parseNoRingFirst()
        assertEquals(own + 5, parse.implementation!!.instructions.count())
        assertAsks(parse, 0, "dropRing", emptyList(), emptyList())
        assertEquals(Opcode.CONST_4, parse.at(3).opcode)
        assertEquals(0, (parse.at(3) as OneRegisterInstruction).registerA)
        assertEquals(0, (parse.at(3) as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN_OBJECT, parse.at(4).opcode)
        assertEquals(0, (parse.at(4) as OneRegisterInstruction).registerA)
        assertEquals(5, offsetTarget(parse, 2))
        // The lock is still the parse's own second instruction, taken only on Facebook's path.
        assertEquals(Opcode.MONITOR_ENTER, parse.at(6).opcode)
        assertTrue("the hook took a lock", (0 until 5).none { parse.at(it).opcode == Opcode.MONITOR_ENTER })
    }

    @Test
    fun `the chat dispatcher hands the extension the notification and returns while Messenger posts it`() {
        // Two locals and three parameters: the notification, parameter 2, is v4.
        val dispatch = MutableMethod(shapes.dispatch())
        val own = dispatch.implementation!!.instructions.count()
        dispatch.dropChatFirst()
        assertEquals(own + 5, dispatch.implementation!!.instructions.count())
        assertEquals(Opcode.MOVE_OBJECT_FROM16, dispatch.at(0).opcode)
        assertEquals(0, (dispatch.at(0) as TwoRegisterInstruction).registerA)
        assertEquals("the hook doesn't hand over the notification", 4, (dispatch.at(0) as TwoRegisterInstruction).registerB)
        assertAsks(dispatch, 1, "dropChat", listOf("Ljava/lang/Object;"), listOf(0))
        assertEquals(Opcode.RETURN_VOID, dispatch.at(4).opcode)
        assertEquals(5, offsetTarget(dispatch, 3))
        assertEquals(Opcode.CONST_STRING, dispatch.at(5).opcode)
    }

    /** A method with no local to borrow is refused by name, before anything goes in. */
    @Test
    fun `a method with no local to borrow stops the patch`() {
        val handle = MutableMethod(shapes.handle(locals = 0))
        val parse = MutableMethod(shapes.parse(locals = 0))
        val dispatch = MutableMethod(shapes.dispatch(locals = 0))
        for (refusal in listOf(
            assertThrows(PatchException::class.java) { handle.dropCallPushFirst() },
            assertThrows(PatchException::class.java) { parse.parseNoRingFirst() },
            assertThrows(PatchException::class.java) { dispatch.dropChatFirst() },
        )) {
            assertTrue(refusal.message, refusal.message!!.contains(ICON_PATCH))
        }
        assertEquals(2, handle.implementation!!.instructions.count())
        assertEquals(5, parse.implementation!!.instructions.count())
        assertEquals(3, dispatch.implementation!!.instructions.count())
    }

    /** The made-up Facebook with the Messenger icon's tap, its button handler and the screen's dispatch too. */
    private fun build(leaveOut: Set<String> = emptySet(), extra: List<ClassDef> = emptyList()) =
        PatchContexts.of(MessengerIconShapesTest.iconClasses() + HandoffShapes.classes(leaveOut, extra) +
            ExtensionDex.classDef(SETTINGS_STATUS) + ExtensionDex.classDef(HANDOFF))

    /** What the extension's MessengerHandoff.inBuild answers after the patch ran. */
    private fun handoffInBuild(context: BytecodePatchContext): Boolean {
        val inBuild = context.mutableClassDefBy(HANDOFF).methods.single { it.name == "inBuild" }
        return (inBuild.at(0) as NarrowLiteralInstruction).narrowLiteral != 0
    }

    @Test
    fun `the patch clears the call registration and asks first in each local call and chat path`() {
        val context = build()
        assertFalse(handoffInBuild(context))
        openMessengerFromTopBarPatch.execute(context)
        assertTrue("the settings page wouldn't offer the switch", handoffInBuild(context))
        val mqtt = context.mutableClassDefBy(MQTT_XPLAT_SERVICE_DELEGATE).methods.single()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, mqtt.at(2).opcode)
        assertEquals("clientCapabilities", mqtt.call(2).name)
        assertEquals(listOf("J"), mqtt.call(2).parameterTypes.map(Any::toString))
        assertEquals("J", mqtt.call(2).returnType)
        assertEquals(11, (mqtt.at(2) as RegisterRangeInstruction).startRegister)
        assertEquals(2, (mqtt.at(2) as RegisterRangeInstruction).registerCount)
        assertEquals(Opcode.MOVE_RESULT_WIDE, mqtt.at(3).opcode)
        assertEquals(11, (mqtt.at(3) as OneRegisterInstruction).registerA)
        assertEquals(Opcode.INVOKE_DIRECT_RANGE, mqtt.at(4).opcode)
        assertEquals("dropCallPush", context.mutableClassDefBy(HandoffShapes.HANDLER).methods.single { it.name == "handle" }.call(0).name)
        assertEquals("dropRing", context.mutableClassDefBy(HandoffShapes.PARSER).methods.single { it.name == "parse" }.call(0).name)
        val manager = context.mutableClassDefBy(MESSAGES_NOTIFICATION_MANAGER).methods
        assertEquals("dropChat", manager.single { it.name == "dispatch" }.call(1).name)
        // The method of the dispatcher's shape that calls no notifier, the claims and the handler
        // of another type are left as they were.
        assertEquals(2, manager.single { it.name == "plain" }.implementation!!.instructions.count())
        assertEquals(Opcode.CONST_STRING,
            context.mutableClassDefBy(HandoffShapes.OTHER_HANDLER).methods.single { it.name == "handle" }.at(0).opcode)
        assertEquals(Opcode.SGET_OBJECT, context.mutableClassDefBy(HandoffShapes.HANDLER).methods.single { it.name == "claim" }.at(0).opcode)
        // #193: the push tray hands the extension the push's type and posts nothing while
        // Messenger takes that chat, and runs Facebook's own code otherwise.
        val post = context.mutableClassDefBy(TRAY_MANAGER).methods.single()
        assertEquals(Opcode.MOVE_OBJECT_FROM16, post.at(0).opcode)
        assertEquals("dropChatPush", post.call(5).name)
        assertEquals(listOf("Ljava/lang/String;"), post.call(5).parameterTypes.map(Any::toString))
        assertEquals(Opcode.RETURN_VOID, post.at(8).opcode)
        assertEquals(9, offsetTarget(post, 7))
        assertEquals(Opcode.CONST_STRING, post.at(9).opcode)
    }

    /**
     * Block promotional notifications goes first in the same post method. With both patches in,
     * each asks in turn, the handoff's no runs on to the promotions' question, and that one's no to
     * Facebook's own first instruction.
     */
    @Test
    fun `with Block promotional notifications too, both ask in the push tray before Facebook posts`() {
        val context = build()
        blockPromotionalNotificationsPatch.execute(context)
        openMessengerFromTopBarPatch.execute(context)
        val post = context.mutableClassDefBy(TRAY_MANAGER).methods.single()
        assertEquals(20, post.implementation!!.instructions.count())
        assertEquals(listOf("dropChatPush", "block"), post.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name
        })
        for (branch in listOf(1, 3, 7)) assertEquals("branch $branch", 9, offsetTarget(post, branch))
        for (branch in listOf(10, 12, 16)) assertEquals("branch $branch", 18, offsetTarget(post, branch))
        // Both read the builder from its own parameter register.
        val builder = HandoffShapes.post().parameterRegisterNumber(BUILDER_PARAMETER)
        assertEquals(builder, (post.at(0) as TwoRegisterInstruction).registerB)
        assertEquals(builder, (post.at(9) as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.CONST_STRING, post.at(18).opcode)
    }

    /**
     * Each handoff anchor this build lacks, or finds twice, leaves every handoff hook out while the
     * Messenger icon's go in, and inBuild stays false so the settings page has no switch that would
     * do nothing (#39): half a handoff would leave the switch saying more than it does.
     */
    @Test
    fun `a build missing a handoff anchor keeps the icon and leaves the whole handoff out`() {
        val broken = mapOf(
            "no NotificationType" to build(leaveOut = setOf(NOTIFICATION_TYPE)),
            "no VOIP constant" to build(leaveOut = setOf(NOTIFICATION_TYPE),
                extra = listOf(shapes.notificationType("MARKETPLACE" to shapes.marketplace))),
            "no VOIP handler" to build(leaveOut = setOf(HandoffShapes.HANDLER)),
            "a VOIP handler with two handle methods" to build(leaveOut = setOf(HandoffShapes.HANDLER), extra = listOf(
                shapes.classDef(HandoffShapes.HANDLER, shapes.claim(), shapes.handle(),
                    shapes.method(HandoffShapes.HANDLER, "handleAgain", shapes.handleParameters, "V", 8, listOf(shapes.returnVoid))))),
            "no parser" to build(leaveOut = setOf(HandoffShapes.PARSER)),
            "two parsers" to build(extra = listOf(
                shapes.classDef("Lfixture/OtherParser;", shapes.ringType("Lfixture/OtherParser;"), shapes.payload("Lfixture/OtherParser;"),
                    shapes.parse("Lfixture/OtherParser;")))),
            "a parser without its parse" to build(leaveOut = setOf(HandoffShapes.PARSER),
                extra = listOf(shapes.classDef(HandoffShapes.PARSER, shapes.ringType(), shapes.payload()))),
            "no MessagesNotificationManager" to build(leaveOut = setOf(MESSAGES_NOTIFICATION_MANAGER)),
            "no dispatcher" to build(leaveOut = setOf(MESSAGES_NOTIFICATION_MANAGER),
                extra = listOf(shapes.classDef(MESSAGES_NOTIFICATION_MANAGER, shapes.dispatch("plain", call = null)))),
            "no MQTT delegate" to build(leaveOut = setOf(MQTT_XPLAT_SERVICE_DELEGATE)),
            "two MQTT connection constructions" to build(leaveOut = setOf(MQTT_XPLAT_SERVICE_DELEGATE), extra = listOf(
                shapes.classDef(MQTT_XPLAT_SERVICE_DELEGATE, shapes.mqttConfig(constructions = 2)))),
            "no push tray" to build(leaveOut = setOf(TRAY_MANAGER)),
            "a builder without the push" to build(leaveOut = setOf(HandoffShapes.TRAY_BUILDER),
                extra = listOf(shapes.classDef(HandoffShapes.TRAY_BUILDER))),
            "a push without its type" to build(leaveOut = setOf(TRAY_NOTIFICATION),
                extra = listOf(shapes.classDef(TRAY_NOTIFICATION))),
        )
        val handoffClasses = listOf(HandoffShapes.HANDLER, "Lfixture/OtherParser;", HandoffShapes.PARSER,
            MESSAGES_NOTIFICATION_MANAGER, MQTT_XPLAT_SERVICE_DELEGATE, TRAY_MANAGER)
        for ((why, context) in broken) {
            openMessengerFromTopBarPatch.execute(context)
            val tap = context.mutableClassDefBy(MessengerIconShapesTest.TAP_CLASS).methods.single()
            assertEquals("$why: the tap doesn't ask first", "open", ((tap.at(2) as ReferenceInstruction).reference as MethodReference).name)
            val dispatch = context.mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single { it.name == "dispatchTouchEvent" }
            assertEquals("$why: the touches don't reach the icon", "touch", ((dispatch.at(0) as ReferenceInstruction).reference as MethodReference).name)
            val present = mutableSetOf<String>()
            context.classDefForEach { present += it.type }
            for (type in handoffClasses.filter { it in present }) {
                for (method in context.mutableClassDefBy(type).methods) {
                    val calls = method.implementation?.instructions.orEmpty().mapNotNull {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { call -> call.definingClass == HANDOFF }
                    }
                    assertTrue("$why: ${method.name} asks the handoff $calls", calls.isEmpty())
                }
            }
            assertFalse("$why: the settings page would offer the switch", handoffInBuild(context))
        }
    }
}
