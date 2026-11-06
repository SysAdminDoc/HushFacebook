/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Where Facebook decides whether a chat opens inside Facebook (its own Chats, Messaging in Blue)
 * or goes to the Messenger app (#392).
 *
 * One static question over the user session, an instance of its own class and a flag answers it:
 * true when MobileConfig, a stored preference or Facebook's own Messenger check puts this
 * account's chats in Facebook. About two dozen callers ask it, among them the Message button's
 * thread routing, chat deep links, the chat list's router, the jewel count and the chat push
 * handlers. InboxOrMessengerRouterActivity, the one of them that keeps its name, asks it with the
 * flag set and, on false, logs "intent_handler_prepare_to_launch_messenger" and starts Messenger
 * instead of Facebook's Chats. An account Meta hasn't moved into Facebook's Chats gets false
 * everywhere, which is how its Message buttons already open Messenger.
 *
 * Facebook's Messenger check counts Messenger only when it's signed with Facebook's own key, so a
 * re-signed Facebook never sees Meta's Messenger there. The gate's class and method are Redex
 * names (`02MU.A02` on 585), so the gate is found from the router's onCreate by its shape.
 */
internal const val INBOX_ROUTER = "Lcom/facebook/messaginginblue/inbox/activities/InboxOrMessengerRouterActivity;"
private const val GATE_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

/** What the patch log and its messages call Open chats in Messenger. */
internal const val CHATS_SWITCH = "Open chats in Messenger"

private fun MethodReference.parameters() = parameterTypes.map(CharSequence::toString)

private fun MethodReference.key() =
    definingClass + "->" + name + parameterTypes.joinToString("", "(", ")") + returnType

/**
 * Whether [call] has the gate's shape: answering a boolean over the session, an instance of the
 * class declaring it, and a boolean.
 */
internal fun isChatsGateCall(call: MethodReference): Boolean =
    call.returnType == "Z" && call.parameters() == listOf(GATE_SESSION, call.definingClass, "Z")

/** The distinct static calls of the gate's shape in [onCreate]. */
internal fun chatsGateCalls(onCreate: Method): List<MethodReference> =
    (onCreate.implementation?.instructions ?: emptyList())
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter(::isChatsGateCall)
        .distinctBy { it.key() }

/** The router's onCreate(Bundle), or null when [methods] has none. */
internal fun routerOnCreate(methods: Iterable<Method>): Method? = methods.singleOrNull {
    it.name == "onCreate" && it.returnType == "V" &&
        it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;")
}

/** Facebook's chats gate, found through the router, or a PatchException naming what this build lacks. */
internal fun BytecodePatchContext.chatsGate(): Method {
    val router = classDefByOrNull(INBOX_ROUTER) ?: throw PatchException("$ICON_PATCH: $INBOX_ROUTER isn't in this build")
    val onCreate = routerOnCreate(router.methods)
        ?: throw PatchException("$ICON_PATCH: $INBOX_ROUTER has no onCreate(Bundle)")
    val calls = chatsGateCalls(onCreate)
    val call = calls.singleOrNull() ?: throw PatchException(
        "$ICON_PATCH: expected $INBOX_ROUTER.onCreate to ask one static (FbUserSession, its own class, Z)Z gate, " +
            "found ${calls.size}",
    )
    val owner = classDefByOrNull(call.definingClass)
        ?: throw PatchException("$ICON_PATCH: the chats gate's class ${call.definingClass} isn't in this build")
    return owner.methods.singleOrNull {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it.name == call.name &&
            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == call.parameters()
    } ?: throw PatchException("$ICON_PATCH: ${call.key()} has no static body in this build")
}
