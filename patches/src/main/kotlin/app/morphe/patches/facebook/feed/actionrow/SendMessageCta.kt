/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.actionrow

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.comments.summaries.holdSummaries
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.patches.facebook.misc.upsells.IMAGINE_CTA_SOCKET
import app.morphe.patches.facebook.misc.upsells.ctaCheck
import app.morphe.patches.facebook.misc.upsells.ctaTable
import com.android.tools.smali.dexlib2.iface.Method

internal const val SEND_MESSAGE_CTA = "$EXTENSION_PACKAGE/feed/SendMessageCta;"
internal const val HIDES_SEND_MESSAGE = "$SEND_MESSAGE_CTA->hidesSendMessage(Ljava/lang/String;)Z"

/**
 * The Messenger "Send message" bar some posts get once you've stayed on them a moment, under the
 * post and over its reaction counts (#609): a plugin of the post call-to-action selector, named in
 * the selector's table like Meta AI's question chips.
 */
internal const val SEND_MESSAGE_PLUGIN = "com.facebook.feed.plugins.calltoaction.impl.messaging.MessagePostAuthorDwellCTAPlugin"

private const val NAME = "Post action buttons"

/** The selector's name [table] and its plugin [check], the one the Send message hook goes first in. */
internal class SendMessageCheck(val table: Method, val check: Method)

/**
 * Finds [SendMessageCheck]: the selector's table by [SEND_MESSAGE_PLUGIN] and its check through the
 * methods naming the selector's socket, as Hide Meta AI questions under posts finds them for its
 * chips. Refuses unless each is found once and the check has a local register for the hook.
 * Changes nothing.
 */
internal fun BytecodePatchContext.findSendMessageCheck(): SendMessageCheck {
    fun holders(string: String) = classDefByStrings(string, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.distinctBy { it.type }
    val table = ctaTable(holders(SEND_MESSAGE_PLUGIN), SEND_MESSAGE_PLUGIN, NAME)
    val sockets = holders(IMAGINE_CTA_SOCKET).flatMap { methodsHolding(it, IMAGINE_CTA_SOCKET) }
    val check = ctaCheck(table, sockets, NAME) { classDefByOrNull(it) }
    if (check.localRegisterCount() < 1) refuse("${check.definingClass}->${check.name} has no local register")
    return SendMessageCheck(table, check)
}

/**
 * First thing in the selector's check, the plugin's name from the table goes to the extension,
 * which answers no for the Send message plugin while its switch is on, so the post goes on to its
 * next button. The hook sits with Hide Meta upsells' and Hide Meta AI questions under posts' of the
 * same shape, each answering no on its own yes and otherwise running on into the next. A build it
 * doesn't fit keeps the rest of the patch, with a warning saying the switch does nothing.
 */
internal fun BytecodePatchContext.hideSendMessage() {
    val found = try {
        findSendMessageCheck()
    } catch (failure: PatchException) {
        patchLog.warning("${failure.message}. Hide Send message under posts does nothing in this build")
        return
    }
    mutableClassDefBy(found.check.definingClass).methods
        .single { it.descriptor() == found.check.descriptor() }
        .holdSummaries(found.table, HIDES_SEND_MESSAGE, NAME)
}
