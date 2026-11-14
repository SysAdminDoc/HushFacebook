/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import com.reandroid.apk.ApkModule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * Hushfacebook settings' Notification icon option (#467): the H it ships, the picture sizes it
 * reads, and Facebook's "f" notification pictures in every declared build's density splits. The
 * fixture half reads the bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without them.
 */
class NotificationIconStyleTest {
    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun riff(chunk: String, payload: ByteArray) =
        "RIFF".toByteArray() + le32(payload.size + 12) + "WEBP".toByteArray() + chunk.toByteArray() + le32(payload.size) + payload

    /** A lossless WebP header for a [width] by [height] picture. */
    private fun lossless(width: Int, height: Int): ByteArray {
        val bits = (width - 1) or ((height - 1) shl 14)
        return riff("VP8L", byteArrayOf(0x2f) + le32(bits) + ByteArray(8))
    }

    /** A lossy WebP header: the frame tag, the start code, then the sizes. */
    private fun lossy(width: Int, height: Int) = riff(
        "VP8 ",
        byteArrayOf(0, 0, 0, 0x9d.toByte(), 0x01, 0x2a, width.toByte(), (width shr 8).toByte(), height.toByte(), (height shr 8).toByte()),
    )

    /** An extended WebP header: flags, then the canvas size less one in 24 bits each. */
    private fun extended(width: Int, height: Int): ByteArray {
        val w = width - 1
        val h = height - 1
        return riff("VP8X", byteArrayOf(0x10, 0, 0, 0, w.toByte(), (w shr 8).toByte(), (w shr 16).toByte(), h.toByte(), (h shr 8).toByte(), (h shr 16).toByte()))
    }

    @Test
    fun `Facebook and an empty option keep the icons`() {
        assertNull(pickedNotificationIcon(null))
        assertNull(pickedNotificationIcon(""))
        assertNull(pickedNotificationIcon("facebook"))
        assertNull(pickedNotificationIcon("Facebook"))
        assertEquals(NotificationIconStyle.HUSHFACEBOOK, pickedNotificationIcon(" hushfacebook "))
        assertEquals(NotificationIconStyle.HUSHFACEBOOK, pickedNotificationIcon("Hushfacebook"))
        assertThrows(PatchException::class.java) { pickedNotificationIcon("bell") }
    }

    @Test
    fun `each shipped H is a square picture of its size, the H cut out of a disc`() {
        for (size in NOTIFICATION_ICON_SIZES) {
            val bytes = notificationIconPicture(size)
            assertEquals("notification_icon_$size.png size", size, squarePictureSize(bytes))
            val image = checkNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "notification_icon_$size.png isn't a PNG" }
            assertTrue("notification_icon_$size.png has no alpha", image.colorModel.hasAlpha())
            fun alpha(x: Double, y: Double) = image.getRGB((x * size).toInt(), (y * size).toInt()) ushr 24
            // Android draws a small icon from its alpha alone: the corners and the H clear, the disc solid.
            assertEquals("$size corner", 0, alpha(0.02, 0.02))
            assertEquals("$size the H's left bar", 0, alpha(0.35, 0.40))
            assertEquals("$size the H's right bar", 0, alpha(0.65, 0.60))
            assertEquals("$size the disc left of the H", 255, alpha(0.15, 0.5))
            assertEquals("$size the disc above the crossbar", 255, alpha(0.5, 0.33))
        }
    }

    @Test
    fun `picture sizes are read from PNG and every WebP header`() {
        assertEquals(96, squarePictureSize(lossless(96, 96)))
        assertEquals(36, squarePictureSize(lossless(36, 36)))
        assertEquals(72, squarePictureSize(lossy(72, 72)))
        assertEquals(48, squarePictureSize(extended(48, 48)))
        assertEquals(24, squarePictureSize(notificationIconPicture(24)))
        assertNull(squarePictureSize(lossless(96, 72)))
        assertNull(squarePictureSize("<vector/>".toByteArray()))
        assertNull(squarePictureSize(ByteArray(0)))
    }

    @Test
    fun `each icon gets the H at its own size, or the next one up`() {
        assertEquals(36, notificationIconSizeFor(36))
        assertEquals(48, notificationIconSizeFor(40))
        assertEquals(96, notificationIconSizeFor(144))
        val writes = notificationIconWrites(
            linkedMapOf(
                "res/drawable-hdpi-v4/sysnotif_facebook.png" to lossless(36, 36),
                "res/drawable-xxxhdpi-v4/sysnotif_facebook.png" to lossless(96, 96),
                // 96 px in an xhdpi folder, as 587's density splits ship it.
                "res/drawable-xhdpi-v4/sysnotif_blue_facebook.png" to lossless(96, 96),
                "res/drawable-hdpi-v4/sysnotif_comment_fb.png" to lossy(72, 72),
            ),
        )
        assertEquals(
            listOf(36, 96, 96, 72),
            writes.values.map { squarePictureSize(it) },
        )
        assertArrayEquals(notificationIconPicture(72), writes.getValue("res/drawable-hdpi-v4/sysnotif_comment_fb.png"))
    }

    @Test
    fun `a missing f icon or one that isn't a picture is refused`() {
        assertThrows(PatchException::class.java) {
            notificationIconWrites(mapOf("res/drawable-hdpi-v4/sysnotif_comment_fb.png" to lossless(72, 72)))
        }
        assertThrows(PatchException::class.java) {
            notificationIconWrites(mapOf("res/drawable-hdpi-v4/sysnotif_facebook.png" to "<vector/>".toByteArray()))
        }
    }

    // ------------------------------------------------------------------ the declared builds

    @Test
    fun `each declared build ships its f notification icons as square pictures`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val glob = Regex("""res/drawable[^/]*/(\w+)\.\w+""")
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val found = mutableMapOf<String, ByteArray>()
                val work = Files.createTempDirectory("hushfacebook-notification").toFile()
                try {
                    ZipFile(bundle).use { zip ->
                        // The pictures live in the density splits, which Morphe merges into the APK it patches.
                        for (entry in zip.entries().toList().filter { it.name.startsWith("split_config.") && it.name.endsWith("dpi.apk") }) {
                            val apk = File(work, entry.name)
                            zip.getInputStream(entry).use { input -> apk.outputStream().use { input.copyTo(it) } }
                            val module = ApkModule.loadApkFile(apk)
                            try {
                                for (file in module.listResFiles()) {
                                    val path = file.buildPath()
                                    val name = glob.matchEntire(path)?.groupValues?.get(1) ?: continue
                                    if (name in FACEBOOK_NOTIFICATION_ICONS) {
                                        found["${entry.name}:$path"] = file.inputSource.openStream().use { it.readBytes() }
                                    }
                                }
                            } finally {
                                module.close()
                            }
                        }
                    }
                } finally {
                    work.deleteRecursively()
                }
                val names = found.keys.map { it.substringAfterLast('/').substringBeforeLast('.') }.toSet()
                assertEquals("${bundle.name}: the f icons", FACEBOOK_NOTIFICATION_ICONS.toSet(), names)
                // What the patch writes for every one of them, so none is refused.
                val writes = notificationIconWrites(found)
                assertEquals("${bundle.name}: every icon gets the H", found.keys, writes.keys)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
