/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/**
 * Route five's table as the resource half writes it: the colour resources the styles give a
 * token's attribute, by value, with every token each stands for.
 */
class AccentResourceBluesTest {
    private val tokens = mapOf(
        "attr_0x7f0404ea" to "ACCENT",
        "attr_0x7f0404fa" to "BLUE_LINK",
        "attr_0x7f0405bd" to "PRIMARY_BUTTON_BACKGROUND",
        "attr_0x7f04062e" to "VERIFIED_BADGE",
        "attr_0x7f040620" to "PRIMARY_TEXT",
    )

    private fun styles(vararg items: Pair<String, String>): Document {
        val text = items.joinToString("") { (name, value) -> "<item name=\"$name\">$value</item>" }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse("<resources><style name=\"s\">$text</style></resources>".byteInputStream())
    }

    private fun id(name: String) = decodedColourId(name)

    @Test
    fun `each blue a token's attribute gets is listed with its tokens and its night value`() {
        val light = styles(
            "attr_0x7f0404ea" to "@color/color_0x7f0601d5",
            "attr_0x7f0405bd" to "@color/color_0x7f0601d5",
            "attr_0x7f04062e" to "@color/color_0x7f0601d5",
            "attr_0x7f0404fa" to "@color/color_0x7f0601d4",
            // Not a blue, not a colour resource, not a token.
            "attr_0x7f040620" to "@color/color_0x7f060100",
            "attr_0x7f0404fa" to "#ff0064d1",
            "android:textColorLink" to "@color/color_0x7f0601d4",
        )
        val dark = styles(
            "attr_0x7f0404fa" to "@color/color_0x7f06044a",
            // A reference to a blue is a blue.
            "attr_0x7f0405bd" to "@color/color_0x7f06027a",
        )
        val colours = mapOf(
            "color_0x7f0601d4" to "#ff0064d1",
            "color_0x7f0601d5" to "#0866ff",
            "color_0x7f06044a" to "#ff0064d1",
            "color_0x7f06027a" to "@color/color_0x7f0601d5",
            "color_0x7f060100" to "#ff1c1e21",
        )
        val night = mapOf("color_0x7f06044a" to "#ff5aa7ff", "color_0x7f0601d4" to "#ff101011")

        assertEquals(
            "7f0601d4=ff0064d1:BLUE_LINK;" +
                "7f0601d5=ff0866ff:ACCENT,PRIMARY_BUTTON_BACKGROUND,VERIFIED_BADGE;" +
                "7f06027a=ff0866ff:PRIMARY_BUTTON_BACKGROUND;" +
                "7f06044a=ff0064d1/ff5aa7ff:BLUE_LINK",
            resourceBluesTable(listOf(light, dark), colours, night, emptySet(), tokens, ::id),
        )
    }

    @Test
    fun `a colour another configuration gives its own value, or with no id, is left out`() {
        val style = styles("attr_0x7f0404ea" to "@color/color_0x7f0601d5", "attr_0x7f0404fa" to "@color/blue_link")
        val colours = mapOf("color_0x7f0601d5" to "#ff0866ff", "blue_link" to "#ff0064d1")
        assertEquals("", resourceBluesTable(listOf(style), colours, emptyMap(), setOf("color_0x7f0601d5"), tokens, ::id))
        assertEquals("7f0601d5=ff0866ff:ACCENT",
            resourceBluesTable(listOf(style), colours, emptyMap(), emptySet(), tokens, ::id))
        assertEquals("7f0601d5=ff0866ff:ACCENT;7f0601d6=ff0064d1:BLUE_LINK",
            resourceBluesTable(listOf(style), colours, emptyMap(), emptySet(), tokens) { name ->
                if (name == "blue_link") 0x7f0601d6L else id(name)
            })
    }
}
