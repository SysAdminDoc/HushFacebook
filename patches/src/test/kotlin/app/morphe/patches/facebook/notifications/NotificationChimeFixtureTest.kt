/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.notifications

import app.morphe.Fixtures
import app.morphe.patches.shared.compat.AppCompatibilities
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.model.ResourceEntry
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #83: the settings row Save Facebook's notification sound copies Facebook's chime out of
 * the APK by its resource name (NotificationSound in the extension). On 582 that's the default of
 * Facebook's ringtone enum, RINGTONE_7, whose resource NotificationChannelsManager hands each
 * category; its three FB_PUSH_NOTIF siblings are alternatives a person can pick inside Facebook.
 * Facebook strips most resource names and splits its raw type, so this holds every declared build
 * to one entry of that name under a raw type the extension looks in, with an MP4 behind it: the
 * container the row's save names the file after and drops the title tag from.
 */
class NotificationChimeFixtureTest {
    @Test
    fun `each declared build carries the chime the notification sound row saves`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun check(bundle: File) {
        val name = bundle.name
        val table = baseApk(bundle) { apk ->
            generateSequence { apk.nextEntry }.first { it.name == TableBlock.FILE_NAME }
            TableBlock.load(apk)
        }
        val chimes = mutableListOf<ResourceEntry>()
        for (block in table.listPackages()) {
            for (pair in block.listSpecTypePairs()) {
                for (resource in pair.resources) {
                    if (resource != null && !resource.isEmpty && resource.name == RESOURCE_NAME) chimes += resource
                }
            }
        }
        assertEquals("$name: ${chimes.map { it.type }}", 1, chimes.size)
        val chime = chimes.single()
        assertTrue("$name: $RESOURCE_NAME is under ${chime.type}", chime.type in RESOURCE_TYPES)

        val file = chime.iterator().asSequence().filterNotNull()
            .first { !it.isNull && it.resConfig.isDefault }.resValue.dataAsString
        val head = baseApk(bundle) { apk ->
            generateSequence { apk.nextEntry }.first { it.name == file }
            apk.readNBytes(12)
        }
        assertEquals("$name: $file is no MP4", "ftyp", String(head, 4, 4, Charsets.US_ASCII))
    }

    /** Reads the bundle's base APK as a stream, once per call, so no copy of it stays in memory. */
    private fun <T> baseApk(bundle: File, read: (ZipInputStream) -> T): T = ZipFile(bundle).use { zip ->
        ZipInputStream(zip.getInputStream(checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }))
            .use(read)
    }

    private companion object {
        /** NotificationSound.RESOURCE_NAME. */
        const val RESOURCE_NAME = "new_facebook_ringtone_7"

        /** NotificationSound.RESOURCE_TYPES. */
        val RESOURCE_TYPES = setOf("raw.2", "raw", "raw.1", "raw.3")
    }
}
