/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Where Facebook decides that a call it was about to start in its own call screen goes to
 * Messenger instead (#568).
 *
 * The router behind the call buttons in Facebook's chats, and the one behind a group call or room
 * link, ask one question over a Context before they start Facebook's call screen. It answers true
 * when the context has no Activity, or when the microphone or the phone permission (the camera
 * only beside one of them) was turned down after Facebook asked for it once. True sends the call
 * to Messenger. A re-signed Facebook never sees Meta's Messenger as installed (ChatsGateAnchors.kt),
 * so that route ends on the Play Store page for Messenger, or on Google's sign-in where the phone
 * has no Google account.
 *
 * The question is an instance method over a Context answering a boolean, the only one in the build
 * that loads all three permission names (`0UZL.A00` on 588, a class of its own).
 */
internal val CALL_PERMISSIONS = listOf(
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.READ_PHONE_STATE",
)

internal const val KEEP_CALL_IN_FACEBOOK = "$HANDOFF->keepCallInFacebook(Landroid/content/Context;)Z"

/** Whether [method] is the call question: an instance method (Context)Z loading all three permission names. */
internal fun isCallPermissionCheck(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == "Z" &&
        method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
        CALL_PERMISSIONS.all { holdsString(method, it) }

/** Facebook's call question, or a PatchException naming how many this build has. */
internal fun BytecodePatchContext.callPermissionCheck(): Method {
    val found = mutableListOf<Method>()
    classDefForEachReferencing(strings = CALL_PERMISSIONS) { classDef ->
        if (!classDef.type.startsWith(EXTENSION_PACKAGE)) found += classDef.methods.filter(::isCallPermissionCheck)
    }
    return found.singleOrNull() ?: throw PatchException(
        "$ICON_PATCH: expected one (Context)Z method loading ${CALL_PERMISSIONS.joinToString()}, found ${found.size}",
    )
}
