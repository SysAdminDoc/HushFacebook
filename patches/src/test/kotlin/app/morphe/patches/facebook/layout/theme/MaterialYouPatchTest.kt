/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.RepoFiles
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The Material You patch's rewrites, on made-up methods and resource files: route three's literal
 * reads, route one's hooks after AMOLED's, route two's night colours, and that each leaves alone
 * what it doesn't recognise.
 */
class MaterialYouPatchTest {
    private fun method(registers: Int, vararg instructions: Instruction, returnType: String = "V") = MutableMethod(
        ImmutableMethod(
            "Lcom/example/Chrome;",
            "draw",
            if (returnType == "I") {
                listOf(ImmutableMethodParameter("I", null, null), ImmutableMethodParameter("Ljava/lang/Object;", null, null))
            } else {
                emptyList()
            },
            returnType,
            AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(registers, instructions.toList(), null, null),
        ),
    )

    private fun store(opcode: Opcode, type: String) =
        ImmutableInstruction21c(opcode, 0, ImmutableFieldReference("Lcom/example/Chrome;", "fill", type))

    private fun Instruction.referenceText() = (this as? ReferenceInstruction)?.reference?.toString()

    @Test
    fun `a dark surface written in code is read from the extension instead`() {
        val method = method(
            2,
            ImmutableInstruction31i(Opcode.CONST, 1, 0xFF252728.toInt()),
            store(Opcode.SPUT, "I"),
            ImmutableInstruction31i(Opcode.CONST, 0, 0xFF101011.toInt()),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        assertEquals(2, method.readSurfaceFields())
        val instructions = method.implementation!!.instructions.toList()
        assertEquals(Opcode.SGET, instructions[0].opcode)
        assertEquals(1, (instructions[0] as OneRegisterInstruction).registerA)
        assertEquals("$MATERIAL_YOU->DARK_252728:I", instructions[0].referenceText())
        assertEquals(Opcode.SGET, instructions[2].opcode)
        assertEquals(0, (instructions[2] as OneRegisterInstruction).registerA)
        assertEquals("$MATERIAL_YOU->DARK_101011:I", instructions[2].referenceText())
    }

    /** The controls: a long, a grey that is also a light-theme colour, and a colour with a hue. */
    @Test
    fun `anything else written in code keeps its value`() {
        val wide = method(
            2,
            ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, 0xFF252728.toInt()),
            store(Opcode.SPUT_WIDE, "J"),
            ImmutableInstruction31i(Opcode.CONST, 0, 0xFF333334.toInt()),
            ImmutableInstruction31i(Opcode.CONST, 0, 0xFF1C2B33.toInt()),
            ImmutableInstruction31i(Opcode.CONST, 0, 0xFF080809.toInt()),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        assertEquals(0, wide.readSurfaceFields())
        val instructions = wide.implementation!!.instructions.toList()
        assertEquals(Opcode.CONST_WIDE_32, instructions[0].opcode)
        assertEquals(0xFF252728.toInt().toLong(), (instructions[0] as WideLiteralInstruction).wideLiteral)
        assertEquals(listOf(0xFF333334.toInt(), 0xFF1C2B33.toInt(), 0xFF080809.toInt()),
            instructions.drop(2).take(3).map { (it as NarrowLiteralInstruction).narrowLiteral })
    }

    /** With AMOLED in the build its hook goes in first, so the palette gets AMOLED's colour. */
    @Test
    fun `a resolver hook goes after AMOLED's, before the return`() {
        val resolver = method(2, ImmutableInstruction11x(Opcode.RETURN, 0), returnType = "I")
        resolver.hookColorReturns(tokenParameterIndex = 1, target = APPLY)
        resolver.hookColorReturns(tokenParameterIndex = 1, target = "$MATERIAL_YOU->fds(ILjava/lang/Object;)I")

        val instructions = resolver.implementation!!.instructions.toList()
        assertEquals(
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.RETURN),
            instructions.map { it.opcode },
        )
        assertEquals(APPLY, instructions[0].referenceText())
        assertEquals("$MATERIAL_YOU->fds(ILjava/lang/Object;)I", instructions[2].referenceText())
    }

    @Test
    fun `a night colour takes the nearest tone only when it is close`() {
        assertEquals(NightTone(accent = false, tone = 90), nightTone("#ffe4e6eb"))
        assertEquals(NightTone(accent = false, tone = 90), nightTone("#E4E6EB"))
        assertEquals(NightTone(accent = false, tone = 70), nightTone("#ffb0b3b8"))
        assertEquals(NightTone(accent = true, tone = 70), nightTone("#ff5aa7ff"))
        assertEquals("@android:color/system_neutral1_100", NightTone(false, 90).systemColor)
        assertEquals("@android:color/system_accent1_300", NightTone(true, 70).systemColor)
        assertEquals("#ffe3e2e9", NightTone(false, 90).fallback)
        assertEquals("#ff97aae4", NightTone(true, 70).fallback)

        // The controls: too far from a tone, black, white, translucent, another hue, a reference.
        for (left in listOf("#ff242526", "#ff252728", "#ff3a3b3c", "#ff000000", "#ffffffff", "#80e4e6eb",
            "#fff02849", "@color/color_0x7f06030a", "")) {
            assertNull(left, nightTone(left))
        }
    }

    private fun xml(text: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())

    private fun Document.text(): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(this), StreamResult(it))
    }.toString()

    private fun Document.colors(): Map<String, String> {
        val colors = getElementsByTagName("color")
        return (0 until colors.length).associate { index ->
            val color = colors.item(index) as Element
            color.getAttribute("name") to color.textContent
        }
    }

    @Test
    fun `route two writes the fixed palette for Android 11 and the system colour for 12 and newer`() {
        val night = xml(
            """
            <resources>
              <color name="color_0x7f060002">#ff242526</color>
              <color name="color_0x7f060003">#ffe4e6eb</color>
              <color name="color_0x7f060004">#ffffffff</color>
              <color name="color_0x7f060005">#ffb0b3b8</color>
              <color name="color_0x7f06044f">#5aa7ff</color>
              <color name="color_0x7f060345">#ff000000</color>
              <color name="color_0x7f060032">@color/color_0x7f06030a</color>
              <color name="color_0x7f060451">#ffe4e6eb</color>
            </resources>
            """.trimIndent(),
        )
        val nightV31 = xml("<resources>\n<color name=\"color_0x7f060451\">@android:color/system_neutral1_50</color>\n</resources>")

        assertEquals(3, recolourNightColours(night, nightV31))
        assertEquals(
            mapOf(
                "color_0x7f060002" to "#ff242526",
                "color_0x7f060003" to "#ffe3e2e9",
                "color_0x7f060004" to "#ffffffff",
                "color_0x7f060005" to "#ffabaab1",
                "color_0x7f06044f" to "#ff97aae4",
                "color_0x7f060345" to "#ff000000",
                "color_0x7f060032" to "@color/color_0x7f06030a",
                // Facebook already gives this one a value of its own for Android 12 and newer.
                "color_0x7f060451" to "#ffe4e6eb",
            ),
            night.colors(),
        )
        assertEquals(
            mapOf(
                "color_0x7f060451" to "@android:color/system_neutral1_50",
                "color_0x7f060003" to "@android:color/system_neutral1_100",
                "color_0x7f060005" to "@android:color/system_neutral1_300",
                "color_0x7f06044f" to "@android:color/system_accent1_300",
            ),
            nightV31.colors(),
        )
    }

    /**
     * The palette adds nothing when there is nothing it recognises: both files come out byte for
     * byte as they went in. With AMOLED first, its black backgrounds are such colours.
     */
    @Test
    fun `route two changes nothing it does not recognise`() {
        val source = "<resources><color name=\"a\">#ff000000</color><color name=\"b\">#ff252728</color>" +
            "<color name=\"c\">#80e4e6eb</color><color name=\"d\">@color/e</color></resources>"
        val night = xml(source)
        val nightV31 = xml("<resources/>")
        val before = night.text() to nightV31.text()
        assertEquals(0, recolourNightColours(night, nightV31))
        assertEquals(before, night.text() to nightV31.text())
    }

    /**
     * Light mode's colours and styles live in values/, which the resource half reads for the FDS
     * styles' night copies. Besides the night files it opens values/colors.xml and values-v31/colors.xml
     * for the palette alone, the names [writeNightStyles] adds.
     */
    @Test
    fun `the resource half writes night resources and the palette`() {
        val dir = "patches/src/main/kotlin/app/morphe/patches/facebook/layout/theme"
        val source = File(RepoFiles.root, "$dir/MaterialYouThemePatch.kt").readText()
        val paths = Regex(""""(res/[^"]+)"""").findAll(source).map { it.groupValues[1] }.toSet()
        // The two globs only decode: patcher 2.0 leaves a file it wasn't asked for undecoded, so it
        // wouldn't exist to read or check.
        assertEquals(setOf("res/values-night/colors.xml", "res/values-night-v31/colors.xml", "res/values-night",
            "res/color-v31", "res/values/colors.xml", "res/values", "res/values-v31/colors.xml", "res/values*/colors.xml",
            "res/values*/style*.xml"), paths)
        assertTrue("the files it reads are decoded first",
            source.contains("decode(\"res/values*/colors.xml\", \"res/values*/style*.xml\", V31_STATE_LISTS)"))
        assertTrue("the state lists are written as files", source.contains("get(\"\$V31_STATE_LISTS/\$name.xml\", false)"))

        val opened = Regex("""document\((\w+)\)""").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("NIGHT_COLORS", "NIGHT_V31_COLORS", "nightStyles", "DEFAULT_COLORS", "V31_COLORS"), opened)
        assertTrue("the night styles are no night file", source.contains("val nightStyles = \"\$NIGHT_VALUES/"))
        val defaults = Regex("""\S*DEFAULT_(?:COLORS|VALUES)\S*""").findAll(source).map { it.value }
            .filterNot { it == "DEFAULT_COLORS" || it == "DEFAULT_VALUES" }.toSet()
        assertEquals(setOf("readOnly(get(DEFAULT_COLORS)).colourValues()", "get(DEFAULT_VALUES).listFiles().orEmpty()",
            "document(DEFAULT_COLORS).use"), defaults)
        assertTrue("only the palette goes in the default colours",
            source.contains("document(DEFAULT_COLORS).use { paletteColours ->"))
        assertTrue("the style files are opened for writing", source.contains("darkFdsStyles(readOnly(file), "))
        assertTrue("the finished resources are checked", source.contains("ownNightOnlyColours(get(\"res\", false))"))

        val styles = File(RepoFiles.root, "$dir/MaterialYouStyles.kt").readText()
        assertTrue("MaterialYouStyles opens a resource file", "\"res/" !in styles && "document(" !in styles)
    }

    /** A theme made at night holds these names after the phone leaves night, and Android throws on one with no value then (issue #323). */
    @Test
    fun `an own colour with only a night value is found`() {
        val res = kotlin.io.path.createTempDirectory("night-only").toFile()
        try {
            fun write(folder: String, vararg names: String) {
                File(res, folder).mkdirs()
                File(res, "$folder/colors.xml").writeText(
                    "<resources>" + names.joinToString("") { "<color name=\"$it\">#ff000000</color>" } + "</resources>",
                )
            }
            write("values", "facebook_grey", "hushfacebook_you_neutral_95", "hushfacebook_amoled_page")
            write("values-night", "facebook_grey", "hushfacebook_amoled_page", "hushfacebook_you_accent_l48", "facebook_night_only")
            write("values-night-v31", "hushfacebook_you_neutral_95", "hushfacebook_you_neutral_50")
            assertEquals("Facebook's own night colours aren't the patch's to answer for",
                setOf("hushfacebook_you_accent_l48", "hushfacebook_you_neutral_50"), ownNightOnlyColours(res))

            write("values", "facebook_grey", "hushfacebook_you_neutral_95", "hushfacebook_amoled_page",
                "hushfacebook_you_accent_l48", "hushfacebook_you_neutral_50")
            assertEquals(emptySet<String>(), ownNightOnlyColours(res))
        } finally {
            res.deleteRecursively()
        }
    }

    /**
     * Route two leaves a colour to Facebook when it has a night value under any night qualifier,
     * not values-night alone. Until 2026-09-30 only values-night/colors.xml was read, so a colour
     * Facebook set for night in values-night-v31 alone would have taken a palette tone.
     */
    @Test
    fun `a night value under any night qualifier counts`() {
        val res = kotlin.io.path.createTempDirectory("night-res").toFile()
        try {
            fun colours(folder: String, vararg names: String) = File(res, folder).apply { mkdirs() }.resolve("colors.xml")
                .writeText(names.joinToString("", "<resources>", "</resources>") { "<color name=\"$it\">#ff000000</color>" })
            colours("values", "light_only")
            colours("values-v31", "light_v31")
            colours("values-night", "night_text")
            colours("values-night-v31", "night_v31_only")
            colours("values-land-night", "night_land_only")
            File(res, "values-nightly").mkdirs()
            File(res, "values-night-v29").mkdirs()
            assertEquals(setOf("night_land_only", "night_text", "night_v31_only"), nightValuedColours(res))
        } finally {
            res.deleteRecursively()
        }
    }

    /** The patch and the extension hold the same lists, each in its own module. */
    @Test
    fun `the patch and the extension agree on the surfaces, the token tables and the fixed palette`() {
        val theme = File(RepoFiles.root, "extensions/facebook/src/main/java/app/morphe/extension/facebook/theme/MaterialYouTheme.java").readText()
        val palette = File(RepoFiles.root, "extensions/facebook/src/main/java/app/morphe/extension/facebook/theme/TonePalette.java").readText()

        val surfaces = Regex("""static final String SURFACES = "([0-9A-F ]+)";""").find(theme)!!.groupValues[1]
            .split(" ").map { it.toInt(16) or -0x1000000 }.toSet()
        assertEquals(SURFACE_FIELDS.keys, surfaces)
        for (field in SURFACE_FIELDS.values) {
            assertTrue("MaterialYouTheme has no field $field", Regex("""public static volatile int $field;""").containsMatchIn(theme))
            assertTrue("MaterialYouTheme never writes $field", theme.contains("$field = surface(p, 0x${field.removePrefix("DARK_")});"))
        }

        val fallback = Regex("""static final String FALLBACK =\s*((?:\s*\+?\s*"[^"]*")+);""").find(palette)!!.groupValues[1]
        val javaText = Regex(""""([^"]*)"""").findAll(fallback).joinToString("") { it.groupValues[1] }
        assertEquals(FALLBACK_PALETTE, javaText)

        for ((java, kotlin) in listOf("FDS_DARK" to FDS_DARK_TOKENS, "FDS_SHARED" to FDS_SHARED_TOKENS)) {
            val table = Regex("""static final String $java =\s*((?:\s*\+?\s*"[^"]*")+);""").find(theme)!!.groupValues[1]
            assertEquals("$java differs", Regex(""""([^"]*)"""").findAll(table).joinToString("") { it.groupValues[1] }, kotlin)
        }
    }
}
