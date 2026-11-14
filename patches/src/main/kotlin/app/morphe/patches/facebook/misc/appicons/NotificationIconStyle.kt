/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.facebook.misc.extension.patchLog

/*
 * The Notification icon option of Hushfacebook settings (#467): Hushfacebook's H, cut out of a
 * disc like Facebook's own "f", on Facebook's notifications. On 587 a push notification's small
 * icon is picked in X.0DEs.A03(SystemTrayNotification) among sysnotif_facebook,
 * sysnotif_blue_facebook, sysnotif_comment_fb, sysnotif_profile_facebook and
 * sysnotif_friend_request, and the login, upload and video notifications use sysnotif_facebook.
 * The four that carry the "f" get the H. The rest show what a notification is about and stay.
 *
 * They're pictures, WebP files named .png in the density splits, and Facebook ships some at a size
 * their folder doesn't say (sysnotif_blue_facebook is 96 px in an xhdpi folder). Each one is
 * overwritten in place by the H at its own pixel size, so the notification code, the resource
 * table and the drawn size stay as they were, and it works in every process, settings or not.
 */

private const val NOTIFICATION_OPTION = "Hushfacebook settings, Notification icon"

/** The looks the Notification icon option offers. */
internal enum class NotificationIconStyle(val key: String, val label: String) {
    FACEBOOK("facebook", "Facebook"),
    HUSHFACEBOOK("hushfacebook", "Hushfacebook"),
}

/** Facebook's notification icons that show its "f", by drawable name. */
internal val FACEBOOK_NOTIFICATION_ICONS =
    listOf("sysnotif_facebook", "sysnotif_blue_facebook", "sysnotif_comment_fb", "sysnotif_profile_facebook")

/** The pixel sizes the H ships at, as notification_icon_<size>.png next to this file. */
internal val NOTIFICATION_ICON_SIZES = listOf(24, 36, 48, 72, 96)

private fun refuseNotification(reason: String): Nothing =
    throw PatchException("$NOTIFICATION_OPTION: $reason. Nothing was changed.")

/** The picked look by key or label, or null for Facebook's own icons. Refuses a name that isn't one. */
internal fun pickedNotificationIcon(raw: String?): NotificationIconStyle? {
    val key = raw?.trim().orEmpty()
    if (key.isEmpty()) return null
    val style = NotificationIconStyle.entries.firstOrNull { it.key.equals(key, ignoreCase = true) || it.label.equals(key, ignoreCase = true) }
        ?: refuseNotification("\"$key\" isn't one. Pick one of ${NotificationIconStyle.entries.joinToString { it.key }}")
    return style.takeIf { it != NotificationIconStyle.FACEBOOK }
}

/** The side of a square PNG or WebP picture, read from its header, or null for anything else. */
internal fun squarePictureSize(bytes: ByteArray): Int? {
    fun u8(i: Int) = bytes[i].toInt() and 0xff
    fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    fun le24(i: Int) = le16(i) or (u8(i + 2) shl 16)
    fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
    fun ascii(i: Int, n: Int) = if (bytes.size >= i + n) String(bytes, i, n, Charsets.US_ASCII) else ""

    val (width, height) = when {
        bytes.size >= 24 && u8(0) == 0x89 && ascii(1, 3) == "PNG" && ascii(12, 4) == "IHDR" -> be32(16) to be32(20)
        ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" && bytes.size >= 30 -> when (ascii(12, 4)) {
            // Lossy: the frame's start code, then 14 bit sizes.
            "VP8 " -> (le16(26) and 0x3fff) to (le16(28) and 0x3fff)
            // Lossless: a signature byte, then width - 1 and height - 1 in 14 bits each.
            "VP8L" -> {
                val bits = le16(21) or (u8(23) shl 16) or (u8(24) shl 24)
                ((bits and 0x3fff) + 1) to (((bits ushr 14) and 0x3fff) + 1)
            }
            // Extended: the canvas width - 1 and height - 1 in 24 bits each.
            "VP8X" -> (le24(24) + 1) to (le24(27) + 1)
            else -> return null
        }
        else -> return null
    }
    return width.takeIf { it == height && it > 0 }
}

/** The shipped size drawn for a [size] px icon: the same, else the next larger, else the largest. */
internal fun notificationIconSizeFor(size: Int) = NOTIFICATION_ICON_SIZES.firstOrNull { it >= size } ?: NOTIFICATION_ICON_SIZES.last()

/** The H at [size] px, from the patch's resources. */
internal fun notificationIconPicture(size: Int): ByteArray {
    val name = "notification_icon_$size.png"
    return NotificationIconStyle::class.java.getResourceAsStream(name)?.use { it.readBytes() }
        ?: refuseNotification("the patch is missing its $name")
}

/**
 * What each of Facebook's "f" notification icons in [files], decoded path to bytes, becomes. Refuses
 * before anything changes when sysnotif_facebook is missing or one of them isn't a square picture.
 */
internal fun notificationIconWrites(files: Map<String, ByteArray>): Map<String, ByteArray> {
    if (files.keys.none { it.substringAfterLast('/').substringBeforeLast('.') == FACEBOOK_NOTIFICATION_ICONS.first() }) {
        refuseNotification("Facebook has no ${FACEBOOK_NOTIFICATION_ICONS.first()} picture")
    }
    return files.mapValues { (path, bytes) ->
        val size = squarePictureSize(bytes) ?: refuseNotification("Facebook's $path isn't a square PNG or WebP picture")
        notificationIconPicture(notificationIconSizeFor(size))
    }
}

/**
 * Draws Hushfacebook's H over Facebook's "f" notification icons when [notificationIcon] answers
 * Hushfacebook. With Facebook's own picked, it opens no file.
 */
internal fun notificationIconPatch(notificationIcon: () -> String?) = resourcePatch {
    execute {
        pickedNotificationIcon(notificationIcon()) ?: return@execute
        // Patcher 2.0 decodes only the files a patch asks for. Shapes would be .xml and are refused.
        val paths = FACEBOOK_NOTIFICATION_ICONS.flatMap { decode("res/drawable*/$it.*") }.distinct()
        paths.firstOrNull { it.endsWith(".xml") }?.let { refuseNotification("Facebook's $it is a drawn shape, not a picture") }
        val writes = notificationIconWrites(paths.associateWith { get(it).readBytes() })
        for ((path, bytes) in writes) get(path).writeBytes(bytes)
        patchLog.info("$NOTIFICATION_OPTION: Hushfacebook's H is on ${writes.size} notification pictures (${writes.keys.joinToString()}).")
    }
}
