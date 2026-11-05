/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.util

import app.morphe.patcher.patch.PatchBundleSignature
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Morphe Manager 2.4.0 pins the key of the first signed bundle it installs from a source and
 * refuses every later bundle that key didn't sign, so a bundle signed by any key but the one
 * morphe-signing.pub publishes would strand everyone who updates. The verifier is what stops it.
 */
class BundleSignatureTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun bundle(): File = folder.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("classes.dex", "extensions/facebook.mpe")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("payload of $name".toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun key() = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    private fun refusal(bundle: File, publishedKey: String?): String =
        runCatching { BundleVerifier.requireSignature(bundle, publishedKey) }.exceptionOrNull()
            .let { failure ->
                assertTrue("expected a refusal, got $failure", failure is IllegalArgumentException)
                failure!!.message.orEmpty()
            }

    @Test
    fun `a bundle signed by the published key passes and names it`() {
        val signed = bundle()
        val publicKey = PatchBundleSignature.sign(signed, key())

        assertEquals(publicKey, BundleVerifier.requireSignature(signed, publicKey))
    }

    @Test
    fun `an unsigned bundle passes whether or not a key is published`() {
        val unsigned = bundle()

        assertNull(BundleVerifier.requireSignature(unsigned, null))
        assertNull(BundleVerifier.requireSignature(unsigned, PatchBundleSignature.sign(bundle(), key())))
    }

    @Test
    fun `a bundle signed by another key is refused`() {
        val signed = bundle()
        val signer = PatchBundleSignature.sign(signed, key())
        val published = PatchBundleSignature.sign(bundle(), key())

        val message = refusal(signed, published)
        assertTrue(message, signer in message && published in message)
    }

    @Test
    fun `a signed bundle is refused when no key is published`() {
        val signed = bundle()
        val signer = PatchBundleSignature.sign(signed, key())

        val message = refusal(signed, null)
        assertTrue(message, signer in message && "publishes no key" in message)
    }

    @Test
    fun `a signed bundle with one byte changed is refused`() {
        val signed = bundle()
        val publicKey = PatchBundleSignature.sign(signed, key())
        val bytes = signed.readBytes()
        bytes[40] = (bytes[40].toInt() xor 1).toByte()
        signed.writeBytes(bytes)

        val message = refusal(signed, publicKey)
        assertTrue(message, "does not match its content" in message)
    }
}
