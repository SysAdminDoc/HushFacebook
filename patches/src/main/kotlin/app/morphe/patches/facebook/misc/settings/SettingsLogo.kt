/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import java.io.File

/** The drawable the settings page's header shows, by the name SettingsHeader in the extension reads. */
internal const val SETTINGS_LOGO = "hushfacebook_settings_logo"

/**
 * A picture the settings patch carries: [png], next to this file in the patch's resources, goes into
 * the app as the drawable [name].
 */
internal class SettingsPicture(val png: String, val name: String)

/**
 * Hushfacebook's logo, 256 px across, so the header's 64 dp frame only ever scales it down, even at
 * 4x screens. It's drawn into the app with the patch, so the header shows it whatever the launcher
 * icon became and whichever other patches were picked.
 */
internal val SETTINGS_PICTURES = listOf(SettingsPicture("settings_logo.png", SETTINGS_LOGO))

private fun refuseLogo(why: String): Nothing = throw PatchException("Hushfacebook settings: $why")

/**
 * The pictures the patch carries, by their path in the decoded res folder. They go in drawable-nodpi,
 * so every screen density gets the one file and the header scales it to its frame. Refuses when one
 * is missing from the patch.
 */
internal fun settingsPictureFiles(): Map<String, ByteArray> = SETTINGS_PICTURES.associateTo(linkedMapOf()) { picture ->
    val bytes = SettingsPicture::class.java.getResourceAsStream(picture.png)?.use { it.readBytes() }
        ?: refuseLogo("the patch is missing its ${picture.png}")
    "drawable-nodpi/${picture.name}.png" to bytes
}

/**
 * Writes the pictures into [res], the decoded res folder. Refuses before anything changes when a file
 * the patch adds is already there.
 */
internal fun addSettingsPictures(res: File) {
    val pictures = settingsPictureFiles()
    pictures.keys.firstOrNull { File(res, it).exists() }?.let { refuseLogo("res/$it is already there") }
    for ((path, bytes) in pictures) File(res, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
}

/** Puts the settings header's logo into the app's resources. */
internal val settingsLogoPatch = resourcePatch {
    execute {
        // Patcher 2.0 decodes only what a patch asks for. Hushfacebook's names are asked for so one
        // already in the APK shows up and is refused.
        decode("res/drawable*/hushfacebook_settings_*.png")
        addSettingsPictures(get("res"))
    }
}
