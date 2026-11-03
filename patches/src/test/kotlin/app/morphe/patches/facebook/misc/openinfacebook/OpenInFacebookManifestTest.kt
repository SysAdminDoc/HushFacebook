/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.openinfacebook

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Open in Facebook from the share menu (#111) on every Facebook build the bundle declares, on the
 * manifest as Morphe decodes it: the build has the link handler the share target hands links to,
 * and the target is declared once, exported, taking shared text. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips the fixture part without it.
 */
class OpenInFacebookManifestTest {
    private fun Document.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun Document.shareTargets() = all("activity").filter { it.getAttribute("android:name") == OPEN_IN_FACEBOOK_ACTIVITY }

    private fun decodedManifest(bundle: File): Document {
        val work = Files.createTempDirectory("hushfacebook-share").toFile()
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
        assertEquals("$where: label", OPEN_IN_FACEBOOK_LABEL, target.getAttribute("android:label"))
        val filter = (target.getElementsByTagName("intent-filter").item(0) as Element)
        fun names(tag: String) = filter.getElementsByTagName(tag).let { list ->
            (0 until list.length).map { (list.item(it) as Element).getAttribute(if (tag == "data") "android:mimeType" else "android:name") }
        }
        assertEquals("$where: action", listOf("android.intent.action.SEND"), names("action"))
        assertEquals("$where: category", listOf("android.intent.category.DEFAULT"), names("category"))
        assertEquals("$where: type", listOf("text/plain"), names("data"))
    }

    @Test
    fun `each declared build has the link handler and gets one share target`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val manifest = decodedManifest(bundle)
                assertEquals("${bundle.name}: link handlers", 1, manifest.all("activity").count { it.getAttribute("android:name") == URI_HANDLER })
                manifest.declareOpenInFacebook()
                manifest.declareOpenInFacebook()
                assertDeclaredOnce(bundle.name, manifest)
            }
        }
    }

    @Test
    fun `a manifest without Facebook's link handler is refused`() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.facebook.katana">
                <application><activity android:name="com.facebook.katana.activity.FbMainTabActivity"/></application>
            </manifest>
            """.trimIndent().byteInputStream(),
        )
        assertThrows(PatchException::class.java) { manifest.declareOpenInFacebook() }
        assertEquals(0, manifest.shareTargets().size)
    }
}
