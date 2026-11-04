/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/**
 * The settings header's logo ships with the settings patch: one square PNG with clear corners, written
 * into res/drawable-nodpi under the name the extension's SettingsHeader looks up.
 */
class SettingsLogoTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun theLogoComesWithThePatch() {
        val files = settingsPictureFiles()
        assertEquals(listOf("drawable-nodpi/hushfacebook_settings_logo.png"), files.keys.toList())
        val image = ImageIO.read(files.values.single().inputStream())
        assertEquals("64 dp at 4x", 256, image.width)
        assertEquals("square", image.width, image.height)
        assertTrue("has alpha", image.colorModel.hasAlpha())
        for ((x, y) in listOf(0 to 0, 255 to 0, 0 to 255, 255 to 255)) {
            assertEquals("clear corner at $x,$y", 0, image.getRGB(x, y) ushr 24)
        }
        val middle = image.getRGB(128, 128)
        assertTrue("the tile is Facebook blue", (middle and 0xFF) > (middle shr 16 and 0xFF) + 100)
    }

    @Test
    fun theLogoGoesIntoDrawableNodpi() {
        val res = folder.newFolder("res")
        addSettingsPictures(res)
        val logo = File(res, "drawable-nodpi/$SETTINGS_LOGO.png")
        assertTrue("written", logo.isFile)
        assertArrayEquals(settingsPictureFiles().values.single(), logo.readBytes())
    }

    @Test
    fun aLogoAlreadyThereIsRefusedBeforeAnythingChanges() {
        val res = folder.newFolder("res")
        val there = File(res, "drawable-nodpi/$SETTINGS_LOGO.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val refusal = assertThrows(PatchException::class.java) { addSettingsPictures(res) }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("already there"))
        assertArrayEquals(byteArrayOf(1, 2, 3), there.readBytes())
    }
}
