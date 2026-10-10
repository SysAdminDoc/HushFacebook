/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/**
 * Facebook's pale blues in light mode, which route five lists by value and route one recolours by
 * value: the two lists have to be the same, or a tint the table answers for would be turned back to
 * Facebook's by route one, or one route one takes would never be in the table.
 */
class AccentTintParityTest {
    private fun styles(vararg items: Pair<String, String>): Document {
        val text = items.joinToString("") { (name, value) -> "<item name=\"$name\">$value</item>" }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse("<resources><style name=\"s\">$text</style></resources>".byteInputStream())
    }

    @Test
    fun `the extension's tint list is the resource half's`() {
        val source = File(RepoFiles.root, "extensions/facebook/src/main/java/app/morphe/extension/facebook/theme/AccentColor.java").readText()
        val literal = Regex("""FACEBOOK_TINTS\s*=\s*\{([^}]*)}""").find(source)
        assertNotNull("AccentColor.java declares no FACEBOOK_TINTS", literal)
        val extension = literal!!.groupValues[1].split(',').map { it.trim().removePrefix("0x").toInt(16) }
        assertEquals(FACEBOOK_TINTS.toList(), extension)
    }

    @Test
    fun `a tint a token's attribute gets is listed, and a pale blue Facebook doesn't use isn't`() {
        val tokens = mapOf("attr_0x7f040501" to "ACCENT_DEEMPHASIZED", "attr_0x7f040502" to "NEW_NOTIFICATION_BACKGROUND")
        val style = styles(
            "attr_0x7f040501" to "@color/color_0x7f0601e4",
            "attr_0x7f040502" to "@color/color_0x7f0601f0",
        )
        val colours = mapOf("color_0x7f0601e4" to "#ffddedfe", "color_0x7f0601f0" to "#ffe3f2fd")
        assertEquals(
            "7f0601e4=ffddedfe:ACCENT_DEEMPHASIZED",
            resourceBluesTable(listOf(style), colours, emptyMap(), emptySet(), tokens, ::decodedColourId),
        )
    }
}
