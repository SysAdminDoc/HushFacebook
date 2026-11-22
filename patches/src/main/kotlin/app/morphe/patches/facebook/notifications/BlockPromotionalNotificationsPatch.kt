/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.notifications

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.executeBytecode
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method

internal const val BLOCK = "$EXTENSION_PACKAGE/notifications/NotificationKinds;->block(Ljava/lang/String;)Z"

/**
 * Asks the extension about a push's type first thing in the method that posts it, and returns
 * before anything is posted when the extension says that kind is blocked. See
 * NotificationAnchors.kt for how Facebook types a push and where it posts one. Every kind without
 * a switch that's on posts as Facebook posts it.
 */
@Suppress("unused")
val blockPromotionalNotificationsPatch = patch(
    name = "Block promotional notifications",
    description = "Blocks the kinds of notification you pick, such as trending videos, memories and birthdays, " +
        "so you get fewer pings you don't care about. Messages, friend requests, comments and login alerts " +
        "always come through. Starts off. Turn on the kinds you want in Hushfacebook settings > Notifications.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    executeBytecode {
        val post = trayPost(PATCH)
        mutableClassDefBy(TRAY_MANAGER).findMutableMethodOf(post.method).postNothingWhenBlocked(post.notification)
        enableStatus("promoNotifications")
    }
}

/** The tray manager's post method and the builder's field holding the push. */
internal class TrayPost(val method: Method, val notification: Field)

/**
 * Finds the post method and the builder's push field (NotificationAnchors.kt), or stops [patch]
 * naming what this build lacks. Block promotional notifications goes first in it, and so does
 * Leave calls and chats to Messenger, for the chats Facebook posts there.
 */
internal fun BytecodePatchContext.trayPost(patch: String): TrayPost {
    val manager = classDefByOrNull(TRAY_MANAGER)
        ?: throw PatchException("$patch: $TRAY_MANAGER isn't in this build")
    val posts = manager.methods.filter(::isPostMethod)
    val post = posts.singleOrNull() ?: throw PatchException(
        "$patch: expected one method of $TRAY_MANAGER loading \"$SHOW_START\" over (Intent, " +
            "NotificationLogObject, NotificationsLogger\$Component, a builder, int), found ${posts.size}",
    )
    val builderType = post.parameterTypes[BUILDER_PARAMETER].toString()
    val builder = classDefByOrNull(builderType)
        ?: throw PatchException("$patch: the post method's builder $builderType isn't in this build")
    val field = notificationField(builder) ?: throw PatchException(
        "$patch: the builder $builderType doesn't hold the push in one field of type $TRAY_NOTIFICATION",
    )
    val notification = classDefByOrNull(TRAY_NOTIFICATION)
    if (notification == null || !hasTypeField(notification)) {
        throw PatchException("$patch: $TRAY_NOTIFICATION has no String field $TYPE_FIELD")
    }
    return TrayPost(post, field)
}

/**
 * First thing in the post method: read the push's type out of the builder, ask the extension's
 * [hook], and return when it says yes. A null builder or push skips the question, and so
 * Facebook's own first instruction runs whatever the answer. The one register borrowed is v0,
 * which holds nothing before the method's first instruction; the builder is copied into it with
 * the 16-bit move, since its parameter register is far past v15. Two patches can each put their
 * question here, one ahead of the other, and both read the same untouched builder register.
 */
internal fun MutableMethod.postNothingWhenBlocked(notification: Field, hook: String = BLOCK, patch: String = PATCH) {
    requireLocals(patch, 1)
    val builder = parameterRegister(BUILDER_PARAMETER)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, $builder
            if-eqz v0, :facebook
            iget-object v0, v0, ${notification.definingClass}->${notification.name}:${notification.type}
            if-eqz v0, :facebook
            iget-object v0, v0, $TRAY_NOTIFICATION->$TYPE_FIELD:Ljava/lang/String;
            invoke-static { v0 }, $hook
            move-result v0
            if-eqz v0, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}
