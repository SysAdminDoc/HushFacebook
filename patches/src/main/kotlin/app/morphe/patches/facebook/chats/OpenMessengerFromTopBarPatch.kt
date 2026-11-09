/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.notifications.TRAY_MANAGER
import app.morphe.patches.facebook.notifications.TrayPost
import app.morphe.patches.facebook.notifications.postNothingWhenBlocked
import app.morphe.patches.facebook.notifications.trayPost
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val OPEN = "$EXTENSION_PACKAGE/chats/MessengerIcon;->open(Landroid/content/Context;Z)Z"
private const val TOUCH = "$EXTENSION_PACKAGE/chats/MessengerIcon;->touch($MOTION_EVENT)V"
internal const val HANDOFF = "$EXTENSION_PACKAGE/chats/MessengerHandoff;"
internal const val DROP_CALL_PUSH = "$HANDOFF->dropCallPush()Z"
internal const val DROP_RING = "$HANDOFF->dropRing()Z"
internal const val DROP_CHAT = "$HANDOFF->dropChat(Ljava/lang/Object;)Z"
internal const val DROP_CHAT_PUSH = "$HANDOFF->dropChatPush(Ljava/lang/String;)Z"
internal const val CLIENT_CAPABILITIES = "$HANDOFF->clientCapabilities(J)J"
internal const val CHATS_IN_MESSENGER = "$HANDOFF->chatsInMessenger()Z"

/**
 * Has a tap on the Messenger icon in Facebook's top bar open the Messenger app, while the switch
 * is on and Messenger is installed, instead of Facebook's own Chats. See MessengerIconAnchors.kt
 * for the tap and the button handler it shares, which the hook goes first in; a tap that takes
 * the handler's route asks again there, and gets the same answer.
 *
 * Only a plain tap goes to Messenger. A long press keeps what Facebook does with it, a preview
 * sheet of chats or Chats itself, so Facebook's own chats stay one gesture away with the switch
 * on. Where Facebook builds the icon without its long-click listener, a held press comes to the tap
 * as a plain tap, so the patch hands every touch to the extension as well, which is how it tells.
 * In the default selection, with the switch off, so nothing changes until it's turned on.
 *
 * The same patch carries the Chats page's second switch, Leave calls and chats to Messenger
 * (issues #39, #100 and #157): Facebook and Messenger both ring for a call and both notify for a message,
 * and with the switch on and Messenger installed only Messenger does. The patch asks the extension
 * first where Facebook supplies its MQTT client capabilities, in Facebook's VOIP push handlers, in
 * the parser every incoming ring goes through, in the dispatcher that hands chat notifications
 * to Facebook's notifiers (MessengerHandoffAnchors.kt), and in the method that posts Facebook's
 * own push notifications (NotificationAnchors.kt), where a message in Facebook's Chats arrives as
 * a push typed MESSAGING_IN_BLUE_DIRECT_MESSAGE and never reaches the dispatcher (#193). Without
 * Messenger, or with it disabled when Facebook starts, the extension leaves the VOIP capability
 * and Facebook rings as before. Every anchor is found before anything changes. A build missing
 * one of the icon's stops the patch; a build missing one of the handoff's keeps the icon, leaves
 * every handoff hook out and says so in the patch log, and the extension's MessengerHandoff.inBuild
 * stays false so the settings page doesn't offer the second switch.
 *
 * The third switch, Open chats in Messenger (#392), goes first in Facebook's chats gate
 * (ChatsGateAnchors.kt) and answers false while it's on and Meta's Messenger is installed, so the
 * Message button on a profile and the rest of Facebook's chat routes open Messenger, as they
 * already do on accounts Meta hasn't moved into Facebook's Chats. A build without the gate keeps
 * the rest, says so in the patch log, and MessengerHandoff.chatsInBuild stays false.
 */
@Suppress("unused")
val openMessengerFromTopBarPatch = bytecodePatch(
    // The README table check reads this literal; ICON_PATCH carries the same text for the messages.
    name = "Open Messenger from the top bar",
    description = "Makes the Messenger icon at the top of Facebook open the Messenger app instead of Facebook's " +
        "own Chats, if you'd rather chat in Messenger. Without Messenger installed, Chats opens as before. " +
        "Starts off. Turn it on in Hushfacebook settings > Chats and Messenger. Also carries Leave calls and chats to " +
        "Messenger, a separate switch that starts off and leaves calls and chat notifications to Messenger " +
        "while it's installed and enabled. Restart Facebook after changing the switch or whether Messenger is " +
        "installed or enabled, so call routing updates. Open chats in Messenger, another switch that starts off, " +
        "opens chats in Messenger instead of inside Facebook, the Message button on a profile included.",
    default = true,
) {
    category("Chats")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // 586 asks a string table for the Reels tab's entry point, so the tap is found by naming it.
        val resolve: (MethodReference) -> Method? = { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
        val taps = methodsNaming(REELS_TAB_ENTRY) { isIconTapShaped(it) }.filter { isIconTap(it, resolve) }
        val tap = taps.singleOrNull() ?: throw PatchException(
            "$ICON_PATCH: expected one static tap method loading \"$NAVBAR_ENTRY\" and \"$REELS_TAB_ENTRY\", " +
                "found ${taps.size}",
        )
        val calls = buttonHandlerCalls(tap).distinctBy { it.toString() }
        val call = calls.singleOrNull() ?: throw PatchException(
            "$ICON_PATCH: expected the tap to call one Messenger button handler " +
                "(Context, FbUserSession, String, [String,] Z, Z)V, found ${calls.size}",
        )
        val handler = classDefByOrNull(call.definingClass)?.let { buttonHandler(it, call) }
            ?: throw PatchException(
                "$ICON_PATCH: the tap's call to $call doesn't reach a static Messenger button handler loading \"$LONG_PRESS\"",
            )
        val tapLongPress = tapLongPress(tap, call) ?: throw PatchException(
            "$ICON_PATCH: the tap doesn't hand $call one of its own flags as the long press",
        )
        val activity = classDefByOrNull(FRAGMENT_ACTIVITY) ?: throw PatchException("$ICON_PATCH: this build has no $FRAGMENT_ACTIVITY")
        val dispatches = touchDispatches(activity)
        val dispatch = dispatches.singleOrNull() ?: throw PatchException(
            "$ICON_PATCH: expected $FRAGMENT_ACTIVITY to declare one dispatchTouchEvent($MOTION_EVENT)Z, found ${dispatches.size}",
        )
        val handoffInBuild = mutableClassDefBy(HANDOFF).methods.singleOrNull {
            it.name == "inBuild" && it.returnType == "Z" && it.parameterTypes.isEmpty()
        } ?: throw PatchException("$ICON_PATCH: the extension has no $HANDOFF->inBuild()")
        val chatsInBuild = mutableClassDefBy(HANDOFF).methods.singleOrNull {
            it.name == "chatsInBuild" && it.returnType == "Z" && it.parameterTypes.isEmpty()
        } ?: throw PatchException("$ICON_PATCH: the extension has no $HANDOFF->chatsInBuild()")
        // The handoff is the second switch's; a build missing one of its anchors keeps the icon.
        val handoff = try {
            handoffTargets()
        } catch (missing: PatchException) {
            patchLog.warning("${missing.message}, so Leave calls and chats to Messenger isn't in this build")
            null
        }
        // The chats gate is the third switch's, and the same goes for it.
        val gate = try {
            chatsGate()
        } catch (missing: PatchException) {
            patchLog.warning("${missing.message}, so $CHATS_SWITCH isn't in this build")
            null
        }
        mutableClassDefBy(tap.definingClass).methods.single { it.isSameAs(tap) }.openMessengerFirst(tapLongPress)
        mutableClassDefBy(handler.definingClass).methods.single { it.isSameAs(handler) }.openMessengerFirst(buttonLongPress(handler))
        mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single { it.isSameAs(dispatch) }.handTouchesToIcon()
        if (handoff != null) {
            mutableClassDefBy(handoff.mqtt.method.definingClass).methods.single { it.isSameAs(handoff.mqtt.method) }
                .leaveCallRegistrationToMessenger(handoff.mqtt.constructionIndex, handoff.mqtt.capabilitiesRegister)
            for (push in handoff.callPushes) {
                mutableClassDefBy(push.definingClass).methods.single { it.isSameAs(push) }.dropCallPushFirst()
            }
            mutableClassDefBy(handoff.ringParse.definingClass).methods.single { it.isSameAs(handoff.ringParse) }.parseNoRingFirst()
            mutableClassDefBy(MESSAGES_NOTIFICATION_MANAGER).methods.single { it.isSameAs(handoff.chatDispatch) }.dropChatFirst()
            mutableClassDefBy(TRAY_MANAGER).methods.single { it.isSameAs(handoff.trayPost.method) }
                .postNothingWhenBlocked(handoff.trayPost.notification, DROP_CHAT_PUSH, ICON_PATCH)
            handoffInBuild.returnEarly(true)
        }
        if (gate != null) {
            mutableClassDefBy(gate.definingClass).methods.single { it.isSameAs(gate) }.leaveChatsToMessengerFirst()
            chatsInBuild.returnEarly(true)
        }
        enableStatus("messengerIcon")
    }
}

/** The methods Leave calls and chats to Messenger goes first in, all found before anything changes. */
internal class HandoffTargets(
    val mqtt: MqttCapabilitiesTarget,
    val callPushes: List<Method>,
    val ringParse: Method,
    val chatDispatch: Method,
    val trayPost: TrayPost,
)

/**
 * Finds the VOIP push handlers' handle methods, the ring parser's parse, the chat dispatcher (see
 * MessengerHandoffAnchors.kt) and the push tray's post method, or stops the patch naming what
 * this build lacks. One pass over the classes finds both the handlers and the parser.
 */
internal fun BytecodePatchContext.handoffTargets(): HandoffTargets {
    val mqttDelegate = classDefByOrNull(MQTT_XPLAT_SERVICE_DELEGATE)
        ?: throw PatchException("$ICON_PATCH: $MQTT_XPLAT_SERVICE_DELEGATE isn't in this build")
    val mqtt = mqttCapabilitiesTarget(mqttDelegate) ?: throw PatchException(
        "$ICON_PATCH: expected one (Z)$MQTT_CONNECTION_CONFIG provider in $MQTT_XPLAT_SERVICE_DELEGATE " +
            "constructing $MQTT_CONNECTION_CONFIG with client capabilities as its first long",
    )
    val notificationType = classDefByOrNull(NOTIFICATION_TYPE)
        ?: throw PatchException("$ICON_PATCH: $NOTIFICATION_TYPE isn't in this build")
    val voip = voipConstant(notificationType)
        ?: throw PatchException("$ICON_PATCH: $NOTIFICATION_TYPE doesn't store a constant named $VOIP")
    val handlers = mutableListOf<ClassDef>()
    val parsers = mutableListOf<ClassDef>()
    // The parser by its kept method names, which reads no code, then the handlers, which read the constant.
    classDefForEach { classDef ->
        if (!classDef.type.startsWith(EXTENSION_PACKAGE) && isRingParser(classDef)) parsers += classDef
    }
    classDefForEachReferencing(types = listOf(voip.definingClass)) { classDef ->
        if (!classDef.type.startsWith(EXTENSION_PACKAGE) && claimsVoip(classDef, voip)) handlers += classDef
    }
    if (handlers.isEmpty()) {
        throw PatchException("$ICON_PATCH: no push handler claims $VOIP pushes alone by reading $voip")
    }
    val callPushes = handlers.map { handler ->
        val handles = handler.methods.filter(::isPushHandle)
        handles.singleOrNull() ?: throw PatchException(
            "$ICON_PATCH: ${handler.type} claims $VOIP pushes but has ${handles.size} handle methods over (Context, " +
                "FbUserSession, PushProperty, the push, a helper, PushInfraMetaData)",
        )
    }
    val parser = parsers.singleOrNull() ?: throw PatchException(
        "$ICON_PATCH: expected one class declaring $RING_TYPE... and $RING_PAYLOAD..., found ${parsers.size}",
    )
    val parses = parser.methods.filter(::isRingParse)
    val ringParse = parses.singleOrNull() ?: throw PatchException(
        "$ICON_PATCH: expected ${parser.type} to have one parse over (FbUserSession, the type, the message, Long, " +
            "byte[]), found ${parses.size}",
    )
    val manager = classDefByOrNull(MESSAGES_NOTIFICATION_MANAGER)
        ?: throw PatchException("$ICON_PATCH: $MESSAGES_NOTIFICATION_MANAGER isn't in this build")
    val dispatches = manager.methods.filter(::isChatDispatch)
    val chatDispatch = dispatches.singleOrNull() ?: throw PatchException(
        "$ICON_PATCH: expected one static dispatcher of $MESSAGES_NOTIFICATION_MANAGER handing a notification to " +
            "the notifiers, found ${dispatches.size}",
    )
    return HandoffTargets(mqtt, callPushes, ringParse, chatDispatch, trayPost(ICON_PATCH))
}

/**
 * Immediately before Facebook constructs its MQTT connection, let the extension remove VOIP from
 * the client capabilities in the constructor's own wide argument register. No local is borrowed.
 */
internal fun MutableMethod.leaveCallRegistrationToMessenger(constructionIndex: Int, capabilitiesRegister: Int) {
    val construction = getInstruction(constructionIndex)
    val call = (construction as? ReferenceInstruction)?.reference as? MethodReference
    if (construction.opcode != Opcode.INVOKE_DIRECT_RANGE ||
        call?.definingClass != MQTT_CONNECTION_CONFIG || call.name != "<init>"
    ) {
        throw PatchException("$ICON_PATCH: the MQTT connection construction moved before injection")
    }
    addInstructionsWithLabels(
        constructionIndex,
        """
            invoke-static/range { v$capabilitiesRegister .. v${capabilitiesRegister + 1} }, $CLIENT_CAPABILITIES
            move-result-wide v$capabilitiesRegister
        """,
    )
}

/**
 * First thing in a VOIP push handler's handle method: ask the extension, and return while it says
 * Messenger takes calls, so the push goes nowhere. v0 is free at index 0.
 */
internal fun MutableMethod.dropCallPushFirst() {
    if (returnType != "V") throw PatchException("$ICON_PATCH: $definingClass->$name isn't a void push handler")
    requireLocals(ICON_PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $DROP_CALL_PUSH
            move-result v0
            if-eqz v0, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * First thing in the ring parser's parse, ahead of the lock it takes: ask the extension, and
 * answer null while it says Messenger takes calls, which every caller reads as nothing to ring.
 * v0 is free at index 0.
 */
internal fun MutableMethod.parseNoRingFirst() {
    if (!returnType.startsWith("L")) throw PatchException("$ICON_PATCH: $definingClass->$name doesn't answer an object")
    requireLocals(ICON_PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $DROP_RING
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * First thing in Facebook's chats gate: ask the extension, and answer false while it says chats
 * open in Messenger, the answer Facebook gives an account whose chats it hands to Messenger.
 * Otherwise the gate runs from its own first instruction. v0 is free at index 0.
 */
internal fun MutableMethod.leaveChatsToMessengerFirst() {
    if (returnType != "Z") throw PatchException("$ICON_PATCH: $definingClass->$name doesn't answer a boolean")
    requireLocals(ICON_PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $CHATS_IN_MESSENGER
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * First thing in the chat dispatcher: hand the extension the notification, copied down into v0
 * with the 16-bit move, and return while it says Messenger posts that kind, so no notifier sees it.
 */
internal fun MutableMethod.dropChatFirst() {
    if (returnType != "V" || parameterTypes.getOrNull(CHAT_NOTIFICATION_PARAMETER)?.toString() != MESSAGING_NOTIFICATION) {
        throw PatchException("$ICON_PATCH: $definingClass->$name isn't a void method taking the notification as parameter " +
            "$CHAT_NOTIFICATION_PARAMETER")
    }
    requireLocals(ICON_PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${parameterRegister(CHAT_NOTIFICATION_PARAMETER)}
            invoke-static { v0 }, $DROP_CHAT
            move-result v0
            if-eqz v0, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * First thing in FbFragmentActivity.dispatchTouchEvent, every touch on a Facebook screen goes to
 * MessengerIcon.touch before Facebook sees it. Where Facebook builds the icon without its
 * long-click listener, a press held on the icon reaches the tap as a plain tap when the finger
 * lifts, and the touch is what tells the extension it was held. Other hooks share the dispatch:
 * Tap to play reads it for its own clock, and the feed's long press and Pinch to zoom turn the rest
 * of a gesture they take into a cancel. This one only reads the event, and the range form passes it
 * without borrowing a register.
 */
internal fun MutableMethod.handTouchesToIcon() {
    val event = parameterRegister(0)
    addInstruction(0, "invoke-static/range { $event .. $event }, $TOUCH")
}

private fun Method.isSameAs(other: Method): Boolean =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)

/**
 * First thing in the tap or the handler: hand the extension the context and the long-press flag,
 * parameter [longPress], and return when it opened Messenger. Otherwise the method runs from its
 * own first instruction. v0 and v1 are free at index 0, and both parameters are copied down into
 * them because `invoke-static` names its registers in four bits.
 */
internal fun MutableMethod.openMessengerFirst(longPress: Int) {
    val parameters = parameterTypes.map(Any::toString)
    if (returnType != "V" || parameters.firstOrNull() != "Landroid/content/Context;" || parameters.getOrNull(longPress) != "Z") {
        throw PatchException(
            "$ICON_PATCH: $definingClass->$name isn't a void method taking the context first and the long press as " +
                "parameter $longPress",
        )
    }
    requireLocals(ICON_PATCH, 2)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, ${parameterRegister(0)}
            move/from16 v1, ${parameterRegister(longPress)}
            invoke-static { v0, v1 }, $OPEN
            move-result v0
            if-eqz v0, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}
