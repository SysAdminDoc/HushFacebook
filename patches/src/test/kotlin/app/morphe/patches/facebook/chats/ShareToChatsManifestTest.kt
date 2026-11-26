/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Share to Facebook chats (#568) on every Facebook build the bundle declares, on the manifest as
 * Morphe decodes it: the share target is declared once, exported and turned off, taking photos,
 * videos and text, and none of Facebook's own Messenger share screens is turned on. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips the fixture part without it.
 */
class ShareToChatsManifestTest {
    private fun Document.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun Document.shareTargets() = all("activity").filter { it.getAttribute("android:name") == SHARE_TO_CHATS_ACTIVITY }

    private fun decodedManifest(bundle: File): Document {
        val work = Files.createTempDirectory("hushfacebook-chats-share").toFile()
        try {
            val apk = File(work, "base.apk")
            ZipFile(bundle).use { zip ->
                val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
                zip.getInputStream(base).use { input -> apk.outputStream().use { input.copyTo(it) } }
            }
            val decoded = File(work, "decoded")
            val module = ApkModule.loadApkFile(apk)
            try {
                ApkModuleXmlDecoder(module).decodeAndroidManifest(decoded)
            } finally {
                module.close()
            }
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(decoded.walk().single { it.name == "AndroidManifest.xml" })
        } finally {
            work.deleteRecursively()
        }
    }

    private fun assertDeclaredOnce(where: String, manifest: Document) {
        val target = manifest.shareTargets().singleOrNull() ?: error("$where: ${manifest.shareTargets().size} share targets")
        assertEquals("$where: exported", "true", target.getAttribute("android:exported"))
        assertEquals("$where: off until the switch", "false", target.getAttribute("android:enabled"))
        assertEquals("$where: label", SHARE_TO_CHATS_LABEL, target.getAttribute("android:label"))
        val filters = target.getElementsByTagName("intent-filter").let { list -> (0 until list.length).map { list.item(it) as Element } }
        fun Element.values(tag: String, attribute: String) = getElementsByTagName(tag).let { list ->
            (0 until list.length).map { (list.item(it) as Element).getAttribute(attribute) }
        }
        val taken = filters.associate { filter ->
            assertEquals("$where: category", listOf("android.intent.category.DEFAULT"), filter.values("category", "android:name"))
            filter.values("action", "android:name").single() to filter.values("data", "android:mimeType")
        }
        assertEquals(
            "$where: what it takes",
            mapOf(
                "android.intent.action.SEND" to listOf("image/*", "video/*", "text/plain"),
                "android.intent.action.SEND_MULTIPLE" to listOf("image/*", "video/*"),
            ),
            taken,
        )
    }

    @Test
    fun `each declared build gets one share target, turned off`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val manifest = decodedManifest(bundle)
                // Facebook's own Messenger share screen has no code in the build, which is why the
                // share target exists; it has to stay off.
                val messengerShare = manifest.all("activity")
                    .filter { it.getAttribute("android:name") == "com.facebook.messenger.intents.MediaEditShareIntentHandler" }
                assertTrue("${bundle.name}: Messenger's share screen", messengerShare.all { it.getAttribute("android:enabled") == "false" })
                manifest.declareShareToChats()
                manifest.declareShareToChats()
                assertDeclaredOnce(bundle.name, manifest)
            }
        }
    }

    @Test
    fun `a manifest without an application is refused`() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.facebook.katana"/>""".byteInputStream(),
        )
        assertThrows(PatchException::class.java) { manifest.declareShareToChats() }
        assertEquals(0, manifest.shareTargets().size)
    }
}
