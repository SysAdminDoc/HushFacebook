/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.gmscore

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * GmsCore support on every Facebook build the bundle declares: one availability check, one framework
 * provider, both ways of asking for a Cloud Messaging token rewritten, and the certificate the patch
 * names is the first of Facebook's signing lineage. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class GmsCoreSupportFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    @Test
    fun everyDeclaredBuildPlansTheSameReroute() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val classes = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex -> classes += dex.classes }
                val plan = planGmsCore(classes)
                val rewritten = plan.texts.map { it.text }.toSet()
                for (text in GMS_CORE_REQUIRED_TEXTS) {
                    assertTrue("${bundle.name}: $text kept", gmsCoreText(text) in rewritten)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The certificate microG-RE shows Google is the first of Facebook's signing lineage. The base APK
     * carries v3 and v3.1 signatures, so the certificates are read out of its APK signing block: the
     * oldest of them is the one pinned.
     */
    @Test
    fun everyDeclaredBuildIsSignedFirstWithThePinnedCertificate() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val base = Files.createTempFile("hushfacebook-gmscore-", ".apk").toFile()
                try {
                    ZipFile(bundle).use { zip ->
                        zip.getInputStream(checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" })
                            .use { input -> base.outputStream().use { input.copyTo(it) } }
                    }
                    val certificates = signingBlockCertificates(base)
                    // scripts/superpack-unpack.ps1 rebuilds the Android 9 build's base.apk unsigned, so that
                    // bundle carries no signing block at all; every bundle Meta signed has to name the pin.
                    if (bundle.name.contains("-storeless")) {
                        assertEquals("${bundle.name} is rebuilt unsigned", null, certificates)
                        continue
                    }
                    assertTrue("${bundle.name}: ${certificates?.size} certificates", !certificates.isNullOrEmpty())
                    val oldest = certificates!!.minBy { it.notBefore }
                    assertEquals("${bundle.name}: ${oldest.subjectX500Principal}", FACEBOOK_CERTIFICATE_SHA1, sha1(oldest))
                } finally {
                    base.delete()
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun sha1(certificate: X509Certificate) =
        MessageDigest.getInstance("SHA-1").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    /**
     * Every DER certificate in the APK signing block, the block that sits right before the central directory,
     * or null when the APK has no signing block.
     */
    private fun signingBlockCertificates(apk: File): List<X509Certificate>? = RandomAccessFile(apk, "r").use { file ->
        val tailSize = minOf(file.length(), 65_557L).toInt()
        val tail = ByteArray(tailSize).also { file.seek(file.length() - tailSize); file.readFully(it) }
        val eocd = (tailSize - 22 downTo 0).first { ByteBuffer.wrap(tail, it, 4).order(ByteOrder.LITTLE_ENDIAN).int == 0x06054b50 }
        val centralDirectory = ByteBuffer.wrap(tail, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val footer = ByteArray(24).also { file.seek(centralDirectory - 24); file.readFully(it) }
        if (String(footer, 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return@use null
        val size = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val block = ByteArray(size.toInt()).also { file.seek(centralDirectory - size); file.readFully(it) }
        val factory = CertificateFactory.getInstance("X.509")
        val found = linkedMapOf<String, X509Certificate>()
        for (start in 0 until block.size - 4) {
            if (block[start] != 0x30.toByte() || block[start + 1] != 0x82.toByte()) continue
            val length = 4 + ((block[start + 2].toInt() and 0xff) shl 8 or (block[start + 3].toInt() and 0xff))
            if (start + length > block.size) continue
            val certificate = runCatching {
                factory.generateCertificate(ByteArrayInputStream(block, start, length)) as X509Certificate
            }.getOrNull() ?: continue
            found.putIfAbsent(sha1(certificate), certificate)
        }
        found.values.toList()
    }
}
