/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Route two for the FDS styles (MaterialYouStyles.kt), on made-up style and colour files shaped like
 * the resource decoder's: which styles it copies for the night, which items it points at the
 * palette, and what it leaves as Facebook has it.
 */
class MaterialYouStylesTest {
    private fun xml(text: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())

    private fun Document.text(): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(this), StreamResult(it))
    }.toString()

    private fun Document.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.items(): Map<String, String> {
        val nodes = getElementsByTagName("item")
        return (0 until nodes.length).associate { index ->
            val item = nodes.item(index) as Element
            item.getAttribute("name") to item.textContent
        }
    }

    /** The real tokens these cases use, plus enough made-up ones for a style to count as a full theme. */
    private val tokens: Map<String, String> =
        listOf("PRIMARY_BUTTON_BACKGROUND", "FB_LOGO", "CARD_BACKGROUND", "ACCENT_DEEMPHASIZED", "PRIMARY_TEXT",
            "ACCENT", "SECONDARY_TEXT", "BLUE_LINK")
            .plus((0 until 310).map { "FILLER_$it" })
            .withIndex().associate { (index, token) -> "attr_0x%08x".format(0x7f040000 + index) to token }

    private fun attribute(token: String) = tokens.entries.single { it.value == token }.key

    private fun fillers() = (0 until 310).joinToString("") { "<item name=\"${attribute("FILLER_$it")}\">#ff123456</item>" }

    private val colours = mapOf(
        "blue" to "#ff0866ff",
        "blue_alias" to "@color/blue",
        "card" to "#ff333334",
        "tint" to "#331d85fc",
        "text" to "#fff2f4f7",
        "night_text" to "#ffb0b3b8",
        "accent" to "#ff1d85fc",
        "link" to "#ff3e93f8",
        "loop" to "@color/loop",
    )

    /** night_text has a night value of its own, which route two or Facebook already decides. */
    private val nightColourNames = setOf("night_text")

    private fun defaults() = xml(
        "<resources>" +
            "<style.2 name=\"light\">" + fillers() +
            "<item name=\"${attribute("PRIMARY_BUTTON_BACKGROUND")}\">@color/blue</item></style.2>" +
            "<style.2 name=\"dark\" parent=\"@style.2/light\">" + fillers() +
            "<item name=\"${attribute("PRIMARY_BUTTON_BACKGROUND")}\">@color/blue_alias</item>" +
            "<item name=\"${attribute("FB_LOGO")}\">@color/blue</item>" +
            "<item name=\"${attribute("CARD_BACKGROUND")}\">#ff333334</item>" +
            "<item name=\"${attribute("ACCENT_DEEMPHASIZED")}\">@color/tint</item>" +
            "<item name=\"${attribute("SECONDARY_TEXT")}\">@color/night_text</item>" +
            "<item name=\"${attribute("PRIMARY_TEXT")}\">@color/loop</item>" +
            "<item name=\"${attribute("ACCENT")}\">@color/accent</item>" +
            "<item name=\"android:background\">?attr/${attribute("PRIMARY_BUTTON_BACKGROUND")}</item>" +
            "</style.2>" +
            "<style.2 name=\"darker\" parent=\"@style.2/dark\">" +
            "<item name=\"${attribute("BLUE_LINK")}\">@color/link</item></style.2>" +
            "<style.2 name=\"dark_extra\" parent=\"@style.2/dark\">" +
            "<item name=\"android:textSize\">12sp</item></style.2>" +
            "<style.2 name=\"darker_child\" parent=\"@style.2/darker\">" +
            "<item name=\"${attribute("PRIMARY_TEXT")}\">@color/text</item></style.2>" +
            "<style.2 name=\"unrelated\" parent=\"@style.2/light\">" +
            "<item name=\"${attribute("PRIMARY_TEXT")}\">@color/text</item></style.2>" +
            "</resources>",
    )

    @Test
    fun `the dark style and every style under it are the family, dark first`() {
        val family = darkFdsStyles(defaults(), tokens).map { it.getAttribute("name") }
        assertEquals("dark", family.first())
        assertEquals(setOf("dark", "darker", "dark_extra", "darker_child"), family.toSet())
    }

    @Test
    fun `a file without Facebook's light and dark themes gives no family`() {
        assertEquals(emptyList<Element>(), darkFdsStyles(xml("<resources><style name=\"a\"/></resources>"), tokens))
        val lightOnly = xml("<resources><style.2 name=\"light\">${fillers()}</style.2></resources>")
        assertEquals(emptyList<Element>(), darkFdsStyles(lightOnly, tokens))
    }

    @Test
    fun `a listed token and colour points at its shade in the night copy`() {
        val source = defaults()
        val night = xml("<resources/>")
        val nightColours = xml("<resources/>")
        val stateLists = mutableMapOf<String, String>()
        val changed = writeNightStyles(darkFdsStyles(source, tokens), colours, nightColourNames, tokens, night, nightColours,
            xml("<resources/>"), stateLists, emptySet())

        val copies = night.elements("style.2").associateBy { it.getAttribute("name") }
        assertEquals("only styles with an item to change are copied", setOf("dark", "darker", "darker_child"), copies.keys)
        assertEquals("@style.2/light", copies.getValue("dark").getAttribute("parent"))

        val dark = copies.getValue("dark").items()
        assertEquals("@color/hushfacebook_you_accent_l48", dark[attribute("PRIMARY_BUTTON_BACKGROUND")])
        assertEquals("@color/hushfacebook_you_neutral_l21", dark[attribute("CARD_BACKGROUND")])
        assertEquals("the logo keeps Facebook's blue", "@color/blue", dark[attribute("FB_LOGO")])
        assertEquals("a translucent tint keeps its lightness and alpha", "@color/hushfacebook_you_accent_l56_a33",
            dark[attribute("ACCENT_DEEMPHASIZED")])
        assertEquals("a colour with a night value stays", "@color/night_text", dark[attribute("SECONDARY_TEXT")])
        assertEquals("a reference loop stays", "@color/loop", dark[attribute("PRIMARY_TEXT")])
        assertEquals("a listed colour between two tones takes its own lightness", "@color/hushfacebook_you_accent_l56",
            dark[attribute("ACCENT")])
        assertEquals("an unlisted colour stays", "#ff123456", dark[attribute("FILLER_0")])
        assertEquals("a theme attribute stays", "?attr/${attribute("PRIMARY_BUTTON_BACKGROUND")}", dark["android:background"])
        assertEquals("every other item is copied", 310 + 8, dark.size)

        assertEquals("@color/hushfacebook_you_accent_l60", copies.getValue("darker").items()[attribute("BLUE_LINK")])
        assertEquals("@color/hushfacebook_you_neutral_l96", copies.getValue("darker_child").items()[attribute("PRIMARY_TEXT")])
        assertEquals(6, changed)

        val shades = listOf(0xFF0866FF, 0xFF333334, 0x331D85FC, 0xFF1D85FC, 0xFF3E93F8, 0xFFF2F4F7)
            .map { checkNotNull(nightShade(it.toInt())) }
        val fallbacks = nightColours.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("each shade is written once", shades.size, nightColours.elements("color").size)
        assertEquals("the shades written", shades.map { it.name }.toSet(), fallbacks.keys)
        assertEquals("each shade has one Android 12 state list", shades.map { it.name }.toSet(), stateLists.keys)
        for (shade in shades) {
            assertEquals(shade.fallback, fallbacks[shade.name])
            assertEquals(shade.stateList, stateLists[shade.name])
        }
    }

    @Test
    fun `a token some code reads as plain data takes a system tone or stays`() {
        val night = xml("<resources/>")
        val nightColours = xml("<resources/>")
        val blue = checkNotNull(nightTone("#ff0866ff"))
        val nightV31Colours = xml("<resources><color name=\"hushfacebook_you_accent_50\">${blue.systemColor}</color></resources>")
        val stateLists = mutableMapOf<String, String>()
        val plain = setOf("PRIMARY_BUTTON_BACKGROUND", "ACCENT", "ACCENT_DEEMPHASIZED", "PRIMARY_TEXT")
        val changed = writeNightStyles(darkFdsStyles(defaults(), tokens), colours, nightColourNames, tokens, night,
            nightColours, nightV31Colours, stateLists, plain)

        val copies = night.elements("style.2").associateBy { it.getAttribute("name") }
        val dark = copies.getValue("dark").items()
        assertEquals("a blue 2 L* from tone 50 is that tone", "@color/hushfacebook_you_accent_50",
            dark[attribute("PRIMARY_BUTTON_BACKGROUND")])
        assertEquals("a blue with no tone within 3 L* stays", "@color/accent", dark[attribute("ACCENT")])
        assertEquals("a translucent colour stays", "@color/tint", dark[attribute("ACCENT_DEEMPHASIZED")])
        assertEquals("a token read as a colour state list keeps its shade", "@color/hushfacebook_you_neutral_l21",
            dark[attribute("CARD_BACKGROUND")])
        assertEquals("@color/hushfacebook_you_neutral_95", copies.getValue("darker_child").items()[attribute("PRIMARY_TEXT")])
        assertEquals("@color/hushfacebook_you_accent_l60", copies.getValue("darker").items()[attribute("BLUE_LINK")])
        assertEquals(4, changed)

        val text = checkNotNull(nightTone("#fff2f4f7"))
        assertEquals("only the other tokens get state lists", setOf("hushfacebook_you_neutral_l21", "hushfacebook_you_accent_l60"),
            stateLists.keys)
        val fallbacks = nightColours.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals(blue.fallback, fallbacks["hushfacebook_you_accent_50"])
        assertEquals(text.fallback, fallbacks["hushfacebook_you_neutral_95"])
        assertEquals(4, fallbacks.size)
        val dynamic = nightV31Colours.elements("color").map { it.getAttribute("name") to it.textContent }
        assertEquals("each tone's system colour is written once for Android 12 and newer",
            listOf("hushfacebook_you_accent_50" to "@android:color/system_accent1_500",
                "hushfacebook_you_neutral_95" to "@android:color/system_neutral1_50"),
            dynamic)
    }

    /**
     * The palette goes into the default colours beside Facebook's, so a theme made at night still
     * finds each name once the phone leaves night (issue #323).
     */
    @Test
    fun `every name a night item points at is in the palette and Facebook's colours stay`() {
        val night = xml("<resources/>")
        val paletteColours = xml("<resources><color name=\"blue\">#ff0866ff</color></resources>")
        writeNightStyles(darkFdsStyles(defaults(), tokens), colours, nightColourNames, tokens, night, paletteColours,
            xml("<resources/>"), mutableMapOf(), setOf("PRIMARY_TEXT"))

        val given = paletteColours.elements("color").map { it.getAttribute("name") to it.textContent }
        assertEquals("Facebook's colour is first and as it was", "blue" to "#ff0866ff", given.first())
        assertTrue("only palette names go in, found $given", given.drop(1).all { it.first.startsWith("hushfacebook_you_") })
        val pointedAt = night.elements("style.2").flatMap { it.items().values }
            .filter { it.startsWith("@color/hushfacebook_you_") }.map { it.removePrefix("@color/") }.toSet()
        assertTrue("a night item takes the palette", pointedAt.isNotEmpty())
        assertEquals(emptySet<String>(), pointedAt - given.map { it.first }.toSet())
    }

    @Test
    fun `a shade keeps the listed colour's lightness and alpha on both routes`() {
        val tint = checkNotNull(nightShade(0x331D85FC))
        assertEquals(NightShade(accent = true, lightness = 56, alpha = 0x33), tint)
        assertEquals("hushfacebook_you_accent_l56_a33", tint.name)
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "    <item android:color=\"@android:color/system_accent1_400\" android:lStar=\"56.0\" android:alpha=\"0.2000\" />\n" +
                "</selector>\n",
            tint.stateList,
        )
        assertTrue("an opaque shade names no alpha", "android:alpha" !in checkNotNull(nightShade(0xFF1D85FC.toInt())).stateList)
        val base = Regex("@android:color/\\w+")
        assertEquals("the darkest grey starts from tone 10, not black",
            "@android:color/system_neutral1_900", base.find(NightShade(false, 3, 0xFF).stateList)!!.value)
        assertEquals("the lightest grey starts from tone 95, not white",
            "@android:color/system_neutral1_50", base.find(NightShade(false, 99, 0xFF).stateList)!!.value)

        // Android 11: the fixed palette's family at the same lightness, with the alpha kept.
        assertTrue(tint.fallback, tint.fallback.startsWith("#33"))
        for (lightness in listOf(5, 21, 48, 56, 73, 96)) {
            for (accent in listOf(true, false)) {
                val fixed = fixedPaletteColour(accent, lightness.toDouble())
                val (r, g, b) = Triple((fixed shr 16) and 0xFF, (fixed shr 8) and 0xFF, fixed and 0xFF)
                assertEquals("L* of $accent $lightness", lightness.toDouble(), lstar(r, g, b), 0.6)
                assertTrue("the fixed palette is blue-tinted at $lightness", b > r)
            }
        }
        assertEquals("a step's own lightness gives that step", 0xFF97AAE4.toInt(),
            fixedPaletteColour(accent = true, lightness = lstar(0x97, 0xAA, 0xE4)))
    }

    @Test
    fun `black, white, a clear colour and other hues take no shade`() {
        for (colour in listOf(0xFF000000, 0xFFFFFFFF, 0x33000000, 0x001D85FC, 0xFF0A2A0A, 0xFFE41E3F)) {
            assertEquals("#%08x".format(colour), null, nightShade(colour.toInt()))
        }
        assertEquals("a translucent grey is a neutral shade", NightShade(false, 21, 0x80), nightShade(0x80333334.toInt()))
    }

    @Test
    fun `the default styles are never changed`() {
        val source = defaults()
        val before = source.text()
        writeNightStyles(darkFdsStyles(source, tokens), colours, nightColourNames, tokens,
            xml("<resources/>"), xml("<resources/>"), xml("<resources/>"), mutableMapOf(), emptySet())
        assertEquals(before, source.text())
    }

    @Test
    fun `a style Facebook already gives a night value of its own is left to it`() {
        val night = xml("<resources><style.2 name=\"dark\"><item name=\"x\">#ff000000</item></style.2></resources>")
        val before = night.text()
        val changed = writeNightStyles(darkFdsStyles(defaults(), tokens), colours, nightColourNames, tokens,
            night, xml("<resources/>"), xml("<resources/>"), mutableMapOf(), emptySet())
        val names = night.elements("style.2").map { it.getAttribute("name") }
        assertEquals(listOf("dark", "darker", "darker_child"), names)
        assertTrue("Facebook's night dark style was changed", night.text().startsWith(before.substringBefore("</resources>")))
        assertEquals(2, changed)
    }

    @Test
    fun `a palette colour already written is not written twice`() {
        val blue = checkNotNull(nightShade(0xFF0866FF.toInt()))
        val nightColours = xml("<resources><color name=\"${blue.name}\">${blue.fallback}</color></resources>")
        val stateLists = mutableMapOf(blue.name to blue.stateList)
        writeNightStyles(darkFdsStyles(defaults(), tokens), colours, nightColourNames, tokens,
            xml("<resources/>"), nightColours, xml("<resources/>"), stateLists, emptySet())
        assertEquals(1, nightColours.elements("color").count { it.getAttribute("name") == blue.name })
        assertEquals(blue.stateList, stateLists[blue.name])
        assertEquals("the other five shades still go in", 6, stateLists.size)
    }

    @Test
    fun `the tables read opaque and translucent colours`() {
        val listed = listedTokenColours()
        assertEquals(setOf(0xFF0866FF.toInt()), listed["PRIMARY_BUTTON_BACKGROUND"])
        assertEquals(setOf(0x331D85FC, 0x192D88FF), listed["ACCENT_DEEMPHASIZED"])
        assertEquals("ACCENT is in both tables, dark-only and shared", setOf(0xFF1D85FC.toInt(), 0xFF0866FF.toInt()), listed["ACCENT"])
        assertTrue("brand tokens are listed in neither", "FB_LOGO" !in listed && "MAP_HIGHLIGHT_BORDER" !in listed)
    }
}
