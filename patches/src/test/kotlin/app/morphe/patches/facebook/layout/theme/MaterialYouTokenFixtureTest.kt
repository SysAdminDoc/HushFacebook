/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.RepoFiles
import app.morphe.patches.facebook.layout.theme.tokenAttributes as patchTokenAttributes
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.classDefForEachReferencing
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ResTableMapEntry
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipFile
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
 * The colours the Material You theme recolours, held to the Facebook builds it declares.
 *
 * <p>MaterialYouTheme.FDS_DARK lists FDS colour tokens with the colour Facebook's dark theme gives
 * each, and the theme recolours a token only when it arrives with exactly that colour. This reads
 * both fixtures: the token enum `FDSColors` resolves (its names and theme attributes, from its
 * static initializer) and the FDS styles in the resource table that set those attributes. Every
 * listed colour has to be one the dark or darker style gives that token, and none may be one any
 * light style gives it, or light mode would change. FDS_SHARED lists the blues a token has in both
 * themes, which wait for Facebook's dark mode answer instead, so each of those has to be the light
 * and the dark style's colour alike. SURFACES, which route three and route four recolour with no
 * token to go on, may be no light style's colour for any token at all.
 *
 * <p>The AMOLED theme's token rule is held to the same styles: each background token it names gets
 * a dark grey it changes.
 */
class MaterialYouTokenFixtureTest {
    private companion object {
        /** WASH in Facebook's dark style, the page under the feed's last unit. */
        const val WASH_DARK = 0xFF101011.toInt()

        const val ANDROID_THEME = 0x01010000
        const val ANDROID_NAME = 0x01010003
        const val WINDOW_BACKGROUND = 0x01010054
        const val MAIN_ACTIVITY = "com.facebook.katana.activity.FbMainTabActivity"

        /**
         * The FDS tokens 577 and 580 resolve and read as `TypedValue.data`: straight from a literal, off a
         * token constant, or through a helper handed the attribute or a token constant as a parameter.
         * 585 no longer reads ACCENT, PLACEHOLDER_IMAGE or WASH that way (2026-10-30, both ABIs).
         */
        val DATA_READ_TOKENS = setOf("DISABLED_TEXT", "DIVIDER", "NAV_BAR_BACKGROUND",
            "PRIMARY_TEXT", "PRIMARY_TEXT_ON_MEDIA", "SURFACE_BACKGROUND")

        /**
         * The tokens 577 and 580 resolve in a method that reads `TypedValue.type` for only some of its
         * calls, or reads neither field and hands the value on. Each was looked at: PRIMARY_TEXT and
         * SURFACE_BACKGROUND are in [DATA_READ_TOKENS] anyway, and SHADOW_TEXT_AND_ICON_ON_MEDIA goes
         * through a helper that returns the TypedValue to a caller reading its `resourceId`. 585
         * resolves none of them in such a method (2026-10-30, both ABIs), and the calls the scan can't
         * follow ([UNRESOLVED]) kept their 18 shapes, so no read moved out of its sight.
         */
        val UNCHECKED_TOKENS = emptySet<String>()

        /**
         * The tokens a styleable array's integer read reaches on 582, through the styles' `?attr/`
         * aliases (StyleableTokens.kt). SECONDARY_TEXT is the likes line's text colour, read with
         * getInteger through the attribute its text styles alias to the token: as a colour state
         * list it crashed the feed on Android 12 and newer (issue #154). ACCENT, DIVIDER,
         * NAV_BAR_BACKGROUND, SURFACE_BACKGROUND and ALWAYS_WHITE are aliased the same way by one
         * style each. The rest come through `android:textColor`, `textColorPrimary` and
         * `textColorSecondary`: three read attributes alias to those, and Facebook's text styles
         * point them at nearly every text token, so the closure takes them all. The scan doesn't
         * tell which theme a style is read against, and a plain colour can't crash the read, so
         * the night styles give every one of them the nearest system tone instead of a state
         * list. Of these the patch recolours thirteen, seven of them plain already as data reads.
         */
        val STYLEABLE_TOKENS = setOf("ACCENT", "ALWAYS_WHITE", "BLUE_LINK", "DECORATIVE_ICON_RED", "DISABLED_TEXT", "DIVIDER",
            "NAV_BAR_BACKGROUND", "NEGATIVE", "NOTIFICATION_BADGE", "PLACEHOLDER_TEXT", "POSITIVE", "PRIMARY_BUTTON_TEXT",
            "PRIMARY_BUTTON_TEXT_ON_COLOR", "PRIMARY_DEEMPHASIZED_BUTTON_TEXT", "PRIMARY_ICON_ON_MEDIA", "PRIMARY_TEXT",
            "PRIMARY_TEXT_ON_MEDIA", "SECONDARY_BUTTON_TEXT", "SECONDARY_ICON", "SECONDARY_TEXT", "SECONDARY_TEXT_ON_MEDIA",
            "SURFACE_BACKGROUND", "TERTIARY_TEXT", "WARNING")

        /**
         * The attribute the likes line's text styles set to `?attr/SECONDARY_TEXT`, by build: the
         * third entry of the styleable array the UFI feedback summary component reads with
         * getInteger (issue #154). The scan has to find it, or the crash is back. The reactions
         * pill bar below reads the same array in its constructor, under a name Redex leaves alone
         * on both ABIs, where the Litho component and the array's holder are renamed per build.
         */
        val LIKES_LINE_ATTRIBUTE = mapOf("585.0.0.47.54" to 0x7f040fcf, "586.0.0.52.53" to 0x7f040fc6)
        /**
         * The seven tokens 584 dropped from 583's 324 (all of them named in MaterialYouTheme's
         * tables or the AMOLED roles at some point), so a listed name missing from a build's enum is
         * fine for these and an error for any other.
         */
        val REMOVED_TOKENS = setOf(
            "FADED_POPOVER_BACKGROUND", "IMAGE_MASK_DARK", "IMAGE_MASK_LIGHT", "NAV_BAR_ICON", "NAV_BAR_TEXT",
            "ROOMS_APP_ICON", "TRANSPARENT",
        )

        /**
         * The FDS_DARK tokens whose listed colour only 583's "darker" style (a child of the dark one
         * that set 47 of the tokens, such as ACCENT #1D85FC) ever gave. 584 has no such style: the dark
         * style's one child sets none of the tokens, so these colours are no longer in the app and
         * the entries are harmless leftovers for 583. A token listed with several colours may likewise
         * have lost the darker style's one, so without a darker style the dark style's own colour only
         * has to be among the listed ones.
         */
        val DARKER_ONLY_LISTED = setOf(
            "ACCENT", "ATTACHMENT_FOOTER_BACKGROUND", "CARD_BORDER", "DISABLED_BUTTON_BACKGROUND",
            "PRIMARY_BUTTON_TEXT", "SECONDARY_BUTTON_BACKGROUND", "TEXT_INPUT_BAR_BACKGROUND",
        )
        const val PILLS_BLING_BAR = "Lcom/facebook/feedback/reactions/ui/PillsBlingBarView;-><init>(Landroid/content/Context;Landroid/util/AttributeSet;I)V"

        /**
         * The calls whose attribute the scan can't follow, by build. React Native's
         * PlatformColor (`A02`, called from FabricUIManager.getColor) looks an attribute up by the name
         * the JavaScript gives it, and Mapbox's ColorUtils looks up colorAccent, colorPrimary and
         * colorPrimaryDark, which aren't FDS tokens. The JavaScript ships compressed, so PlatformColor's
         * names were logged on 580 instead (2026-09-30): Marketplace home, a listing, search and its
         * results asked it for none, since React Native's colours there arrive as ints.
         *
         * The rest are in methods that check `type` for only some calls or hand the TypedValue on, and
         * none is an FDS token (looked at on 580, 2026-09-30): `A2O.A0Q` and `FVu.A00` read only
         * `resourceId`; `fA7.Eu1` passes attributes it keeps in fields to `gi9.A00`, which reads a
         * dimension; `g4w.A05`, `g7C`'s constructor and `g7C.A0Z` read `gAp.errorColorAttr`, a text
         * input's border states, whose four attributes (0x7f040456, 0x7f040459, 0x7f040448, 0x7f040458)
         * the token enum doesn't hold; and `fFB.D8G`'s text colours come from builder fields that
         * `g60.A0V`, `g65.A01` and `hk8.invoke` only ever set to 0x7f040458, its third call an image
         * resource through `giD.A01`. 577 has the same code under other names (8Qk, iv6, PHA, PJ4,
         * QPv, QS3), and `kWD.A1B` is Mapbox's and MapLibre's ColorUtils lookup by name, kept in one
         * helper there. 581 has the same 18 calls under other names (looked at 2026-10-02): `Cuj.A02`,
         * `FMh.A00`, `c0D.A0K`, `emC.EwX` (whose six calls ask `gZV.A00`, a dimension read like
         * `gi9.A00`), `epS.DA9`, `fjh.A04`, `fmc`'s constructor and `fmc.A0f`, and Mapbox's three.
         * 582 has the same 18 calls with the same signatures, each within four instructions of where
         * 581 had it (2026-10-10): `CM1.A02`, `ksB.A00`, `9MV.A0Q`, `S2K.EzN`, `S47.DCV`, `U0q.A04`,
         * `U3I`'s constructor and `U3I.A0f`, and Mapbox's three. The 32-bit build has them under
         * other names again, a few at an instruction or two apart, so each build has its own list.
         * 583 keeps the same 18 on both ABIs (2026-10-16), arm64's `9Sj.A0Q` to `nJl.A00` and
         * armeabi-v7a's `9Tq.A0Q` to `nZb.A00`. From 585 on, the scan's list is compared by its
         * [keptShape]s, which both ABIs share ([SHAPES_585]).
         */
        private val MAPBOX = setOf(
            "Lcom/mapbox/mapboxsdk/utils/ColorUtils;->getAccentColor(Landroid/content/Context;)I@13",
            "Lcom/mapbox/mapboxsdk/utils/ColorUtils;->getPrimaryColor(Landroid/content/Context;)I@13",
            "Lcom/mapbox/mapboxsdk/utils/ColorUtils;->getPrimaryDarkColor(Landroid/content/Context;)I@13",
        )
        /**
         * 585's 18 are the same calls at the same offsets on both ABIs (2026-10-30), arm64's
         * `0BlF.A0P`, `0EFm.A02`, `0UiT.Jc8`, `0UkR.Hfn`, `0WPB.A04`, `0WQk`'s constructor and
         * `0WQk.A0e`, `0uoc.A00` and Mapbox's three, so they're pinned as [keptShape]s, one list
         * for both, with no name a single build made up.
         */
        private val SHAPES_585 = MAPBOX.toList() + listOf(
            "L?;->?(Ljava/lang/Integer;)V@16",
            "L?;->?(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Integer;@88",
            "L?;->?(Landroid/content/Context;Lcom/facebook/react/bridge/ReadableMap;)Landroid/graphics/drawable/Drawable;@20",
            "L?;->?(L?;)V@155",
            "L?;->?(L?;)V@169",
            "L?;->?(L?;)V@54",
            "L?;->?(L?;)V@67",
            "L?;->?(L?;)V@79",
            "L?;->?(L?;)V@82",
            "L?;->?(L?;I)V@169",
            "L?;->?(L?;I)V@226",
            "L?;->?(L?;I)V@32",
            "L?;->?(L?;L?;)V@9",
            "L?;-><init>(Landroid/content/Context;)V@116",
            "L?;->?()V@35",
        )
        /**
         * 586's 18 are 585's, shape for shape, each within seven instructions of where 585 had it
         * (2026-11-09): the session null checks 586 puts first move them.
         */
        private val SHAPES_586 = MAPBOX.toList() + listOf(
            "L?;->?(Ljava/lang/Integer;)V@16",
            "L?;->?(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Integer;@88",
            "L?;->?(Landroid/content/Context;Lcom/facebook/react/bridge/ReadableMap;)Landroid/graphics/drawable/Drawable;@20",
            "L?;->?(L?;)V@151",
            "L?;->?(L?;)V@165",
            "L?;->?(L?;)V@50",
            "L?;->?(L?;)V@63",
            "L?;->?(L?;)V@75",
            "L?;->?(L?;)V@78",
            "L?;->?(L?;I)V@171",
            "L?;->?(L?;I)V@228",
            "L?;->?(L?;I)V@33",
            "L?;->?(L?;L?;)V@11",
            "L?;-><init>(Landroid/content/Context;)V@109",
            "L?;->?()V@36",
        )
        /** 586's Android 9 build has the same 18, the six taking one object and the constructor three or four instructions on (2026-11-10). */
        private val SHAPES_586_ANDROID_9 = MAPBOX.toList() + listOf(
            "L?;->?(Ljava/lang/Integer;)V@16",
            "L?;->?(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Integer;@88",
            "L?;->?(Landroid/content/Context;Lcom/facebook/react/bridge/ReadableMap;)Landroid/graphics/drawable/Drawable;@20",
            "L?;->?(L?;)V@154",
            "L?;->?(L?;)V@168",
            "L?;->?(L?;)V@54",
            "L?;->?(L?;)V@67",
            "L?;->?(L?;)V@79",
            "L?;->?(L?;)V@82",
            "L?;->?(L?;I)V@171",
            "L?;->?(L?;I)V@228",
            "L?;->?(L?;I)V@33",
            "L?;->?(L?;L?;)V@11",
            "L?;-><init>(Landroid/content/Context;)V@112",
            "L?;->?()V@36",
        )
        val UNRESOLVED = mapOf(
            "585.0.0.47.54" to mapOf("arm64-v8a" to SHAPES_585, "armeabi-v7a" to SHAPES_585),
            "586.0.0.52.53" to mapOf("arm64-v8a" to SHAPES_586, "armeabi-v7a" to SHAPES_586,
                "arm64-v8a-minapi28-storeless" to SHAPES_586_ANDROID_9),
        )

        /** A class name Redex made up for one build, or a merged lambda group's. */
        private val REDEX_TYPE = Regex("""L[XY]/[0-9A-Za-z_$]+;""")

        /**
         * [entry], a scan's `method@instruction`, with every name Redex made up for one build blanked:
         * such a class reads `L?;` and its method `?` (a constructor keeps `<init>`), and kept names stay.
         */
        fun keptShape(entry: String): String {
            val owner = entry.substringBefore("->")
            val method = entry.substringAfter("->")
            val name = method.substringBefore('(')
            val redex = REDEX_TYPE.matches(owner)
            return (if (redex) "L?;" else owner) + "->" + (if (redex && !name.startsWith("<")) "?" else name) +
                method.substring(name.length).replace(REDEX_TYPE, "L?;")
        }
    }

    private val theme = File(RepoFiles.root,
        "extensions/facebook/src/main/java/app/morphe/extension/facebook/theme/MaterialYouTheme.java").readText()

    private val amoled = File(RepoFiles.root,
        "extensions/facebook/src/main/java/app/morphe/extension/facebook/theme/AmoledTheme.java").readText()

    /**
     * Token name to the colours the theme recolours, from one of the Java tables. Six hex digits are
     * opaque and eight carry their alpha, as MaterialYouTheme.parseTokens reads them.
     */
    private fun listedTokens(constant: String = "FDS_DARK"): Map<String, Set<Int>> {
        val start = theme.indexOf("static final String $constant =")
        check(start >= 0) { "MaterialYouTheme declares no $constant" }
        val end = theme.indexOf("\";", start)
        val table = Regex(""""([^"]*)"""").findAll(theme.substring(start, end + 1)).joinToString("") { it.groupValues[1] }
        return table.split(";").associate { entry ->
            val (name, values) = entry.split("=")
            name to values.split(",").map { if (it.length == 8) it.toLong(16).toInt() else it.toInt(16) or -0x1000000 }.toSet()
        }
    }

    private fun listedSurfaces(): Set<Int> =
        Regex("""static final String SURFACES = "([0-9A-F ]+)";""").find(theme)!!.groupValues[1]
            .split(" ").map { it.toInt(16) or -0x1000000 }.toSet()

    @Test
    fun `every recoloured token and surface is dark-only in each declared build`() {
        val listed = listedTokens()
        assertTrue("the table lists almost nothing", listed.size > 50)
        val surfaces = listedSurfaces()
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk -> checkBuild(fixture.name, apk, listed, surfaces) }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    private fun checkBuild(build: String, apk: File, listed: Map<String, Set<Int>>, surfaces: Set<Int>) {
        val attributes = tokenAttributes(apk)
        assertTrue("$build: the token enum has too few constants", attributes.size > 300)
        val styles = fdsStyles(apk, attributes.values.toSet())

        // The light theme sets every token and has no parent; dark is its child that sets them all
        // again, and darker is dark's child. 584 has no darker style that sets tokens: dark's one
        // child (7f2004cd) sets none of the 317, so [darker] is null there.
        val light = styles.values.single { it.parent == 0 && it.sets > 300 }
        val dark = styles.values.single { it.parent == light.id && it.sets > 300 }
        val darker = styles.values.singleOrNull { it.parent == dark.id }
        val lightSide = styles.values.filter { it.id != dark.id && it.id != darker?.id }

        for ((name, colours) in listed) {
            val attribute = attributes[name] ?: if (name in REMOVED_TOKENS) continue else error("$build: no FDS token $name")
            val darkColours = setOfNotNull(dark.values[attribute], darker?.values?.get(attribute))
            assertTrue("$build: $name is listed with ${hex(colours)}, dark gives ${hex(darkColours)}",
                darkColours.containsAll(colours) || (darker == null && (name in DARKER_ONLY_LISTED || darkColours.all { it in colours })))
            for (style in lightSide) {
                val lightColour = style.values[attribute] ?: continue
                assertTrue("$build: style ${Integer.toHexString(style.id)} gives $name the listed ${hex(setOf(lightColour))}",
                    lightColour !in colours)
            }
        }

        val lightColours = lightSide.flatMap { it.values.values }.toSet()
        for (surface in surfaces) {
            assertTrue("$build: ${hex(setOf(surface))} is a light style's colour", surface !in lightColours)
        }
    }

    /**
     * The page under the feed's last unit in dark mode: FbMainTabActivity's theme, through its
     * parents, sets `android:windowBackground` to `?attr/WASH`, and the dark style gives WASH
     * #101011, one of SURFACES. The framework draws that background, so MaterialYouTheme.recolourWindow
     * gives it the palette from an activity callback rather than a hook.
     */
    @Test
    fun `the main activity's window background is WASH, dark's #101011, in each declared build`() {
        assertTrue("SURFACES lists #101011", WASH_DARK in listedSurfaces())
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk -> checkWindowBackground(fixture.name, apk) }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    private fun checkWindowBackground(build: String, apk: File) {
        val attributes = tokenAttributes(apk)
        val wash = attributes["WASH"] ?: error("$build: no FDS token WASH")
        val styles = fdsStyles(apk, attributes.values.toSet())
        val light = styles.values.single { it.parent == 0 && it.sets > 300 }
        val dark = styles.values.single { it.parent == light.id && it.sets > 300 }
        assertEquals("$build: dark's WASH", WASH_DARK, dark.values[wash])

        val byId = resources(apk)
        var style = mainActivityTheme(apk)
        var found = false
        var depth = 0
        while (!found && style ushr 24 == 0x7f && depth++ < 40) {
            val entries = checkNotNull(byId[style]) { "$build: theme %08x isn't in the table".format(style) }
                .iterator().asSequence().filter { it != null && !it.isNull && it.isComplex }.toList()
            for (entry in entries) {
                for (item in entry.tableEntry as ResTableMapEntry) {
                    if (item.nameId != WINDOW_BACKGROUND) continue
                    val where = "$build: style %08x %s windowBackground".format(style, entry.resConfig)
                    assertEquals(where, ValueType.ATTRIBUTE, item.valueType)
                    assertEquals(where, wash, item.data)
                    found = true
                }
            }
            style = (entries.firstOrNull { it.resConfig.isDefault }?.tableEntry as? ResTableMapEntry)?.parentId ?: 0
        }
        assertTrue("$build: FbMainTabActivity's theme sets no windowBackground", found)
    }

    /** The theme the manifest gives FbMainTabActivity. */
    private fun mainActivityTheme(apk: File): Int = ZipFile(apk).use { zip ->
        val manifest = ResXmlDocument()
        zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { manifest.readBytes(it) }
        fun ResXmlElement.attribute(id: Int) = (0 until attributeCount).map { getAttributeAt(it) }.firstOrNull { it.nameId == id }
        val activity = manifest.recursiveElements().asSequence().filterIsInstance<ResXmlElement>().single {
            it.name == "activity" && it.attribute(ANDROID_NAME)?.valueString == MAIN_ACTIVITY
        }
        checkNotNull(activity.attribute(ANDROID_THEME)) { "$MAIN_ACTIVITY names no theme" }.data
    }

    private fun resources(apk: File): Map<Int, ResourceEntry> {
        val table = ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry(TableBlock.FILE_NAME)).use { TableBlock.load(it) } }
        val byId = mutableMapOf<Int, ResourceEntry>()
        for (block in table.listPackages()) {
            for (pair in block.listSpecTypePairs()) {
                for (resource in pair.resources) if (resource != null && !resource.isEmpty) byId[resource.resourceId] = resource
            }
        }
        return byId
    }

    /**
     * Issue #37: FDS_SHARED lists the blues Facebook gives a token in both themes, which only
     * Facebook's dark mode answer can tell apart. Each listed colour has to be the one the light
     * style gives that token and the one the dark style gives it too, and no colour of a token may
     * be in both tables, or a shared blue would skip that answer.
     */
    @Test
    fun `every shared blue is the token's colour in both themes in each declared build`() {
        val shared = listedTokens("FDS_SHARED")
        assertTrue("the shared table lists almost nothing", shared.size >= 10)
        val dark = listedTokens()
        for ((name, colours) in shared) {
            val twice = colours intersect dark[name].orEmpty()
            assertTrue("$name lists ${hex(twice)} in both tables", twice.isEmpty())
        }
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk ->
                    val attributes = tokenAttributes(apk)
                    val styles = fdsStyles(apk, attributes.values.toSet())
                    val light = styles.values.single { it.parent == 0 && it.sets > 300 }
                    val darkStyle = styles.values.single { it.parent == light.id && it.sets > 300 }
                    for ((name, colours) in shared) {
                        val attribute = attributes[name] ?: if (name in REMOVED_TOKENS) continue else error("${fixture.name}: no FDS token $name")
                        for (colour in colours) {
                            assertEquals("${fixture.name}: $name in the light style", colour, light.values[attribute])
                            assertEquals("${fixture.name}: $name in the dark style", colour, darkStyle.values[attribute])
                        }
                    }
                }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    /**
     * Issue #141: FDS_LIGHT is, for each token FDS_DARK lists, the colour the light FDS style gives
     * it when that is a grey other than white and black, one of Facebook's blues or one of its pale
     * tints, and no dark style gives the token that colour, so the Video tab's dark colours stay out.
     * The table is built here from each fixture and has to match the Java one exactly, and no colour
     * of a token is in both FDS_LIGHT and FDS_SHARED.
     */
    @Test
    fun `every light colour the theme recolours is the light style's and no dark style's in each declared build`() {
        val listed = listedTokens("FDS_LIGHT")
        val dark = listedTokens()
        val shared = listedTokens("FDS_SHARED")
        for ((name, colours) in listed) {
            assertTrue("$name is in FDS_LIGHT but not FDS_DARK", name in dark)
            val twice = colours intersect shared[name].orEmpty()
            assertTrue("$name lists ${hex(twice)} in FDS_LIGHT and FDS_SHARED", twice.isEmpty())
        }
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk ->
                    val attributes = tokenAttributes(apk)
                    val styles = fdsStyles(apk, attributes.values.toSet())
                    val light = styles.values.single { it.parent == 0 && it.sets > 300 }
                    val darkStyle = styles.values.single { it.parent == light.id && it.sets > 300 }
                    val darker = styles.values.singleOrNull { it.parent == darkStyle.id }
                    val built = sortedMapOf<String, Int>()
                    for (name in dark.keys) {
                        val attribute = attributes[name] ?: if (name in REMOVED_TOKENS) continue else error("${fixture.name}: no FDS token $name")
                        val colour = light.values[attribute] ?: continue
                        val darkColours = setOfNotNull(darkStyle.values[attribute], darker?.values?.get(attribute))
                        if (colour !in darkColours && lightThemed(colour)) built[name] = colour
                    }
                    val table = built.entries.joinToString(";") { (name, colour) ->
                        name + "=" + if (colour ushr 24 == 0xFF) "%06X".format(colour and 0xFFFFFF) else "%08X".format(colour)
                    }
                    assertEquals("${fixture.name}: FDS_LIGHT should be \"$table\"", built.mapValues { setOf(it.value) }, listed.filterKeys { it !in REMOVED_TOKENS }.toSortedMap())
                }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
        assertTrue("the light table lists almost nothing", listed.size > 20)
    }

    /** MaterialYouTheme's light half's colours: a grey but white and black, a Facebook blue, or a pale tint of one. */
    private fun lightThemed(colour: Int): Boolean {
        val rgb = colour and 0xFFFFFF
        if (rgb == 0xFFFFFF || rgb == 0) return false
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        if (maxOf(r, g, b) - minOf(r, g, b) <= 10) return true
        if (rgb in setOf(0xDDEDFE, 0xE7F3FF, 0xEBF5FF)) return true
        // MaterialYouTheme.isFacebookBlue: hue 200 to 225 degrees, saturation a quarter, value 0.3.
        val delta = b - minOf(r, g)
        if (b < r || b < g || b < 77 || delta * 4 < b) return false
        val turn = 60 * (r - g)
        return turn >= -40 * delta && turn <= -15 * delta
    }

    /**
     * Issue #37: SERVER_BLUES, which routes two and four recolour with no token to go on, are
     * Facebook's own palette and nobody else's: each is a colour one of the FDS styles gives some
     * token, at some alpha.
     */
    @Test
    fun `every server blue is a colour Facebook's styles give a token in each declared build`() {
        val blues = Regex("""static final String SERVER_BLUES = "([0-9A-F ]+)";""").find(theme)!!.groupValues[1]
            .split(" ").map { it.toInt(16) }
        assertTrue("SERVER_BLUES lists almost nothing", blues.size >= 5)
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk ->
                    val attributes = tokenAttributes(apk)
                    val rgb = fdsStyles(apk, attributes.values.toSet()).values
                        .flatMap { it.values.values }.map { it and 0xFFFFFF }.toSet()
                    for (blue in blues) {
                        assertTrue("${fixture.name}: ${"%06X".format(blue)} is no FDS style's colour", blue in rgb)
                    }
                }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    /** AmoledTheme's BACKGROUND_TOKENS, the Mig names in it included. */
    private fun amoledBackgrounds(): Set<String> {
        val start = amoled.indexOf("BACKGROUND_TOKENS =")
        val end = amoled.indexOf(")));", start)
        return Regex(""""([A-Z_]+)"""").findAll(amoled.substring(start, end)).map { it.groupValues[1] }.toSet()
    }

    private fun amoledChannel(name: String): Int =
        Regex("""static final int $name = 0x([0-9A-F]+);""").find(amoled)?.groupValues?.get(1)?.toInt(16)
            ?: error("AmoledTheme declares no $name")

    /**
     * Issue #27: under AMOLED, Facebook's cards stayed #333334 on the black page. Each background
     * token AMOLED names that the dark or darker style gives a dark grey (an opaque grey below mid
     * grey) has to be one AMOLED changes: black up to MAX_CHANNEL, near black up to
     * MAX_RAISED_CHANNEL. CARD_BACKGROUND has to be above the black band, or the raised band would
     * go untested.
     */
    @Test
    fun `every dark background AMOLED names is a grey it darkens in each declared build`() {
        val backgrounds = amoledBackgrounds()
        assertTrue("BACKGROUND_TOKENS lists almost nothing", backgrounds.size > 20)
        val black = amoledChannel("MAX_CHANNEL")
        val raised = amoledChannel("MAX_RAISED_CHANNEL")
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk ->
                    val attributes = tokenAttributes(apk)
                    val styles = fdsStyles(apk, attributes.values.toSet())
                    val light = styles.values.single { it.parent == 0 && it.sets > 300 }
                    val dark = styles.values.single { it.parent == light.id && it.sets > 300 }
                    val darker = styles.values.singleOrNull { it.parent == dark.id }

                    val raisedTokens = mutableSetOf<String>()
                    for (name in backgrounds) {
                        val attribute = attributes[name] ?: continue
                        for (style in listOfNotNull(dark, darker)) {
                            val colour = style.values[attribute] ?: continue
                            val channels = listOf(colour shr 16 and 0xFF, colour shr 8 and 0xFF, colour and 0xFF)
                            if (colour ushr 24 != 0xFF || channels.max() - channels.min() > 8 || channels.max() >= 0x80) continue
                            assertTrue("${fixture.name}: $name is ${hex(setOf(colour))} in style ${Integer.toHexString(style.id)}, " +
                                "above the greys AMOLED darkens (0x${Integer.toHexString(raised)})", channels.max() <= raised)
                            if (channels.max() > black) raisedTokens += name
                        }
                    }
                    assertTrue("${fixture.name}: CARD_BACKGROUND is no longer above the black band, found $raisedTokens",
                        "CARD_BACKGROUND" in raisedTokens)
                }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    /** AmoledTheme's LIGHT_BACKGROUND_TOKENS, the fills only light mode's Light background color moves. */
    private fun amoledLightBackgrounds(): Set<String> {
        val start = amoled.indexOf("LIGHT_BACKGROUND_TOKENS =")
        check(start >= 0) { "AmoledTheme declares no LIGHT_BACKGROUND_TOKENS" }
        return Regex(""""([A-Z_]+)"""").findAll(amoled.substring(start, amoled.indexOf(")));", start))).map { it.groupValues[1] }.toSet()
    }

    private val lightBackground = File(RepoFiles.root,
        "extensions/facebook/src/main/java/app/morphe/extension/facebook/theme/AmoledLightBackground.java").readText()

    /**
     * Issue #214: in light mode the Light background color moves each background AMOLED names that
     * holds a light grey (an opaque grey with every channel LIGHT_MIN_CHANNEL or more). No dark or
     * darker style gives one of those tokens such a grey, so the Video tab, dark in light mode, keeps
     * its colours. The light style gives a card white, every fill LIGHT_BACKGROUND_TOKENS adds a light
     * grey, a grey button the grey AmoledLightBackground holds text to, and a comment's bubble and the
     * composer's bar a grey no darker than that button.
     */
    @Test
    fun `every light grey AMOLED moves in light mode is no dark style's in each declared build`() {
        val lightOnly = amoledLightBackgrounds()
        val backgrounds = amoledBackgrounds() + lightOnly
        assertTrue("LIGHT_BACKGROUND_TOKENS lists nothing", lightOnly.isNotEmpty())
        val floor = amoledChannel("LIGHT_MIN_CHANNEL")
        val textSurface = Regex("""static final int TEXT_SURFACE_GREY = 0x([0-9A-F]+);""").find(lightBackground)
            ?.groupValues?.get(1)?.toLong(16)?.toInt() ?: error("AmoledLightBackground declares no TEXT_SURFACE_GREY")
        fun lightGrey(colour: Int): Boolean {
            val channels = listOf(colour shr 16 and 0xFF, colour shr 8 and 0xFF, colour and 0xFF)
            return colour ushr 24 == 0xFF && channels.min() >= floor && channels.max() - channels.min() <= 8
        }
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk ->
                    val attributes = tokenAttributes(apk)
                    val styles = fdsStyles(apk, attributes.values.toSet())
                    val light = styles.values.single { it.parent == 0 && it.sets > 300 }
                    val dark = styles.values.single { it.parent == light.id && it.sets > 300 }
                    val darker = styles.values.singleOrNull { it.parent == dark.id }
                    fun lightColour(name: String) =
                        light.values[attributes[name] ?: error("${fixture.name}: no FDS token $name")]

                    for (name in backgrounds) {
                        val attribute = attributes[name] ?: continue
                        for (style in listOfNotNull(dark, darker)) {
                            val colour = style.values[attribute] ?: continue
                            assertTrue("${fixture.name}: $name is ${hex(setOf(colour))} in style ${Integer.toHexString(style.id)}, " +
                                "a light grey the Light background color would move", !lightGrey(colour))
                        }
                    }
                    assertEquals("${fixture.name}: a card", -1, lightColour("CARD_BACKGROUND"))
                    for (name in lightOnly) {
                        val colour = lightColour(name)
                        assertTrue("${fixture.name}: $name is ${colour?.let { hex(setOf(it)) }} in light mode, no light grey",
                            colour != null && lightGrey(colour))
                    }
                    for (name in listOf("SECONDARY_BUTTON_BACKGROUND", "SECONDARY_BUTTON_BACKGROUND_OPAQUE")) {
                        assertEquals("${fixture.name}: $name", textSurface, lightColour(name))
                    }
                    for (name in listOf("COMMENT_BACKGROUND", "TEXT_INPUT_BAR_BACKGROUND")) {
                        val colour = checkNotNull(lightColour(name)) { "${fixture.name}: no light $name" }
                        assertTrue("${fixture.name}: $name ${hex(setOf(colour))} is darker than a grey button",
                            (0..16 step 8).all { (colour shr it and 0xFF) >= (textSurface shr it and 0xFF) })
                    }
                }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    /** AmoledTheme's Java set [constant], from its declaration to the end of it. */
    private fun amoledSet(constant: String): Set<String> {
        val start = amoled.indexOf("$constant =")
        check(start >= 0) { "AmoledTheme declares no $constant" }
        return Regex(""""([A-Z_]+)"""").findAll(amoled.substring(start, amoled.indexOf(")));", start))).map { it.groupValues[1] }.toSet()
    }

    /**
     * Issue #214's reviewer gaps: the blue text roles light mode's Light background color holds past
     * TEXT_TOKENS. Each is an FDS token whose light style colour FDS_LIGHT lists and whose dark style
     * sets another, so the Video tab's dark blues keep theirs. ACCENT isn't one, since its light blue
     * is the dark style's too. Each of Mig's light fills is a constant of a Mig colour enum and no
     * FDS token's, so the name alone says it's Mig's.
     */
    @Test
    fun `every blue text role and Mig fill light mode adds is light mode's alone in each declared build`() {
        val text = amoledSet("LIGHT_TEXT_TOKENS")
        val fills = amoledSet("MIG_LIGHT_FILLS")
        assertTrue("LIGHT_TEXT_TOKENS, found $text", "TEXT_INPUT_ACTIVE_TEXT" in text && "ACCENT" !in text)
        assertTrue("MIG_LIGHT_FILLS, found $fills", "FILL" in fills)
        val lightTable = listedTokens("FDS_LIGHT")
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk ->
                    val attributes = tokenAttributes(apk)
                    val styles = fdsStyles(apk, attributes.values.toSet())
                    val light = styles.values.single { it.parent == 0 && it.sets > 300 }
                    val dark = styles.values.single { it.parent == light.id && it.sets > 300 }
                    val darker = styles.values.singleOrNull { it.parent == dark.id }
                    for (name in text) {
                        val attribute = attributes[name] ?: error("${fixture.name}: no FDS token $name")
                        val colour = light.values[attribute]
                        assertTrue("${fixture.name}: $name is ${colour?.let { hex(setOf(it)) }} in light mode, which FDS_LIGHT doesn't list",
                            colour != null && colour in lightTable[name].orEmpty())
                        val darkColour = dark.values[attribute]
                        assertTrue("${fixture.name}: the dark style gives $name ${darkColour?.let { hex(setOf(it)) }}",
                            darkColour != null && darkColour != colour)
                        darker?.values?.get(attribute)?.let {
                            assertTrue("${fixture.name}: the darker style gives $name light mode's blue", it != colour)
                        }
                    }
                    attributes["ACCENT"]?.let { accent ->
                        assertEquals("${fixture.name}: ACCENT's light blue is the dark style's", light.values[accent],
                            dark.values[accent] ?: light.values[accent])
                    }
                    val names = migColourNames(apk)
                    assertTrue("${fixture.name}: Mig's enums have no ${fills - names}", names.containsAll(fills))
                    assertTrue("${fixture.name}: FDS tokens named ${fills intersect attributes.keys}", (fills intersect attributes.keys).isEmpty())
                }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    /**
     * Issue #214's light screens, route two, on each declared build's own styles and colours written
     * out as the resource decoder names them. The patch gives light mode's page, cards, comments and
     * composer bar surface resources of their own, each a white or light grey the light style reads
     * through the default colours alone, under the name of its colour, and changes nothing in the
     * dark style's family or in any other item. Text or icons read the same whites, which is why
     * the surfaces get resources of their own.
     */
    @Test
    fun `the light surfaces the patch repoints are light mode's alone in each declared build`() {
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk ->
                    val build = fixture.name
                    val attributes = tokenAttributes(apk)
                    val tokens = attributes.entries.associate { (token, attribute) -> "attr_0x%08x".format(attribute) to token }
                    val decoded = decode(apk)
                    assertTrue("$build: a night colour isn't a configured one", decoded.configuredColours.containsAll(decoded.nightColours))
                    val surfaces = mutableMapOf<String, Int>()
                    val moved = mutableSetOf<String>()
                    val shared = mutableSetOf<String>()
                    var files = 0
                    for (file in decoded.styleFiles.values) {
                        val (light, dark) = fdsThemeStyles(file, tokens) ?: continue
                        files++
                        fun snapshot() = file.documentElement.elements().associate { style ->
                            style.getAttribute("name") to style.elements().map { it.getAttribute("name") to it.textContent }
                        }
                        val before = snapshot()
                        surfaces += repointLightSurfaces(file, tokens, decoded.colours, decoded.configuredColours)
                        val after = snapshot()
                        val lightName = light.getAttribute("name")
                        assertEquals("$build: a style other than the light one changed", before - lightName, after - lightName)
                        val darkSets = dark.elements().map { it.getAttribute("name") }.toSet()
                        val stock = before.getValue(lightName).toMap()
                        val repointed = mutableSetOf<String>()
                        for ((attribute, value) in after.getValue(lightName)) {
                            val was = stock.getValue(attribute)
                            if (value == was) continue
                            val token = tokens[attribute] ?: error("$build: the light style's $attribute changed, no FDS token")
                            assertTrue("$build: $token is no light surface", token in LIGHT_SURFACE_TOKENS)
                            assertTrue("$build: the dark style doesn't set $token", attribute in darkSets)
                            val colour = checkNotNull(defaultColour(was, decoded.colours, decoded.configuredColours)) { "$build: $token's $was" }
                            assertTrue("$build: $token's ${hex(setOf(colour))} is no light grey", isLightSurface(colour))
                            assertEquals("$build: $token", "@color/$LIGHT_SURFACE" + "%06x".format(colour and 0xFFFFFF), value)
                            moved += token
                            repointed += was
                        }
                        for ((attribute, value) in after.getValue(lightName)) {
                            val token = tokens[attribute] ?: continue
                            if (token !in LIGHT_SURFACE_TOKENS && value in repointed) shared += token
                        }
                    }
                    assertEquals("$build: style files with Facebook's light and dark themes", 1, files)
                    assertTrue("$build: the page, cards, comments and the composer's bar, found $moved",
                        moved.containsAll(setOf("SURFACE_BACKGROUND", "CARD_BACKGROUND", "COMMENT_BACKGROUND", "TEXT_INPUT_BAR_BACKGROUND")))
                    assertTrue("$build: a surface resource takes a name Facebook has", surfaces.keys.none { it in decoded.colours })
                    assertTrue("$build: no text or icon reads the surfaces' colours, so they need no resources of their own",
                        shared.isNotEmpty())
                }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    /** AmoledTheme's TEXT_TOKENS: FDS's names, then Mig's, as its comments mark them. */
    private fun amoledTextRoles(): Pair<Set<String>, Set<String>> {
        val start = amoled.indexOf("TEXT_TOKENS =")
        val block = amoled.substring(start, amoled.indexOf(")));", start))
        val mig = block.indexOf("// Mig.")
        check(start >= 0 && mig >= 0) { "AmoledTheme's TEXT_TOKENS has no Mig part" }
        fun names(text: String) = Regex(""""([A-Z_]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
        return names(block.substring(0, mig)) to names(block.substring(mig))
    }

    /** AmoledThemeTest's ROLE_COLOURS, the colours its #34 matrix lifts, as listedTokens reads a table. */
    private fun roleColours(): Map<String, Set<Int>> {
        val test = File(RepoFiles.root,
            "extensions/facebook/src/test/java/app/morphe/extension/facebook/theme/AmoledThemeTest.java").readText()
        val start = test.indexOf("static final String ROLE_COLOURS =")
        check(start >= 0) { "AmoledThemeTest declares no ROLE_COLOURS" }
        val table = Regex(""""([^"]*)"""").findAll(test.substring(start, test.indexOf("\";", start) + 1))
            .joinToString("") { it.groupValues[1] }
        return table.split(";").associate { entry ->
            val (name, values) = entry.split("=")
            name to values.split(",").map { it.toInt(16) or -0x1000000 }.toSet()
        }
    }

    /** The constant names of each Mig colour enum the dark scheme's (token) resolver takes, from their static initializers. */
    private fun migColourNames(apk: File): Set<String> {
        val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        val classes = mutableMapOf<String, ClassDef>()
        for (name in container.dexEntryNames) {
            for (classDef in container.getEntry(name)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
        }
        val tokenTypes = classes.getValue(DARK_COLOR_SCHEME).methods
            .filter { it.returnType == "I" && it.parameterTypes.size == 1 && it.parameterTypes[0].toString() != "Ljava/lang/Integer;" }
            .map { it.parameterTypes[0].toString() }.toSet()
        return classes.values
            .filter { it.superclass == "Ljava/lang/Enum;" && it.interfaces.any { type -> type in tokenTypes } }
            .flatMap { enum ->
                enum.methods.single { it.name == "<clinit>" }.implementation!!.instructions
                    .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
            }.toSet()
    }

    /**
     * Issue #34: on a lighter Background colour AMOLED lifts text and icon roles by their token's
     * name, so the roles are held to each declared build before a colour is let through. Each FDS
     * role has to be a token whose dark and darker styles give it only colours AmoledThemeTest's
     * matrix lifts to 4.5:1 on #5B513F's surfaces (ROLE_COLOURS), and each of those colours has to
     * be one the styles give. Each of Mig's short names has to be a constant of a Mig colour enum
     * the dark scheme resolves, and no FDS token's, so the name alone says it's a text role.
     */
    @Test
    fun `every text role AMOLED lifts is a token with the colours its matrix covers in each declared build`() {
        val (fds, mig) = amoledTextRoles()
        assertTrue("FDS's text roles", fds.size >= 14 && "SECONDARY_TEXT" in fds && "PLACEHOLDER_TEXT" in fds)
        assertTrue("Mig's text roles", "SECONDARY" in mig && "PLACEHOLDER" in mig)
        val matrix = roleColours()
        assertEquals("the matrix covers every FDS role", fds, matrix.keys)
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk ->
                    val attributes = tokenAttributes(apk)
                    val styles = fdsStyles(apk, attributes.values.toSet())
                    val light = styles.values.single { it.parent == 0 && it.sets > 300 }
                    val dark = styles.values.single { it.parent == light.id && it.sets > 300 }
                    val darker = styles.values.singleOrNull { it.parent == dark.id }
                    for (name in fds) {
                        val attribute = attributes[name] ?: if (name in REMOVED_TOKENS) continue else error("${fixture.name}: no FDS token $name")
                        val colours = setOfNotNull(dark.values[attribute], darker?.values?.get(attribute))
                        // 584 has no darker style, so a colour only that one gave (PRIMARY_TEXT's white) is
                        // gone and the matrix is allowed to cover more than the styles give.
                        if (darker == null) {
                            assertTrue("${fixture.name}: $name's dark colours ${hex(colours)} aren't in the matrix",
                                colours.isNotEmpty() && matrix.getValue(name).containsAll(colours))
                        } else {
                            assertEquals("${fixture.name}: $name's dark colours", matrix.getValue(name), colours)
                        }
                    }
                    val names = migColourNames(apk)
                    assertTrue("${fixture.name}: Mig's enums have no ${mig - names}", names.containsAll(mig))
                    assertTrue("${fixture.name}: FDS tokens named ${mig intersect attributes.keys}", (mig intersect attributes.keys).isEmpty())
                }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    /**
     * The night copies of the FDS styles (MaterialYouStyles.kt), which Find friends on an empty Pages
     * feed takes its blue from, on each declared build's own styles and colours written out as the
     * resource decoder names them. The patch has to read the token attributes this test reads, copy
     * only the dark style's family, leave the default styles as they are, and point an item at the
     * palette only for a token and colour FDS_DARK or FDS_SHARED lists. PRIMARY_BUTTON_BACKGROUND in
     * the dark style has to be one of them.
     *
     * <p>Some of Facebook's code resolves a token's attribute and reads `TypedValue.data` as its colour,
     * which a colour state list doesn't give. The patch finds those tokens in each build's dex, the
     * same [DATA_READ_TOKENS] in both, and each item it changes for one of them has to be a plain
     * colour, while every other item keeps its state list. What the scan can't settle is pinned as
     * well ([UNCHECKED_TOKENS], [UNRESOLVED]), so a new build's changes there get looked at.
     */
    @Test
    fun `every night style item the patch changes is a listed token and colour in each declared build`() {
        val dark = listedTokens()
        val shared = listedTokens("FDS_SHARED")
        val listed = (dark.keys + shared.keys).associateWith { dark[it].orEmpty() + shared[it].orEmpty() }
        val builds = mutableListOf<String>()
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk -> checkNightStyles(fixture.name, version, apk, listed) }
                builds += fixture.name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("one fixture for each declared build", declaredBuilds(), Fixtures.abisOf(builds).size)
    }

    private fun checkNightStyles(build: String, version: String, apk: File, listed: Map<String, Set<Int>>) {
        val attributes = tokenAttributes(apk)
        val (initializer, tokenType) = tokenInitializer(apk)
        assertEquals("$build: the patch reads other token attributes", attributes, patchTokenAttributes(initializer, tokenType))
        val tokens = attributes.entries.associate { (token, attribute) -> "attr_0x%08x".format(attribute) to token }
        val scan = scannedTokens(apk, initializer, tokenType)
        assertEquals("$build: the tokens read as TypedValue data", DATA_READ_TOKENS, scan.tokens)
        assertEquals("$build: the tokens resolved where the read isn't checked", UNCHECKED_TOKENS, scan.unchecked.keys)
        assertEquals("$build: the data reads the scan can't follow",
            UNRESOLVED[version]?.get(build.substringAfter("-$version-").substringBefore(".apkm"))?.sorted(),
            scan.unresolved.map { keptShape(it) }.sorted())

        val styles = fdsStyles(apk, attributes.values.toSet())
        val light = styles.values.single { it.parent == 0 && it.sets > 300 }
        val darkStyle = styles.values.single { it.parent == light.id && it.sets > 300 }
        val decoded = decode(apk)
        val styleable = styleableTokens(apk, version, decoded.styleFiles.values.toList(), tokens)
        assertEquals("$build: the tokens a styleable array's integer read reaches", STYLEABLE_TOKENS, styleable)
        val plain = scan.tokens + styleable

        var restyled = 0
        val darkTokens = mutableSetOf<String>()
        val plainTones = mutableSetOf<String>()
        for ((type, file) in decoded.styleFiles) {
            val family = darkFdsStyles(file, tokens)
            if (family.isEmpty()) continue
            assertEquals("$build: the family starts at another style", "${type}_0x%08x".format(darkStyle.id),
                family.first().getAttribute("name"))
            val names = family.map { it.getAttribute("name") }.toSet()
            assertTrue("$build: the light style is in the family", "${type}_0x%08x".format(light.id) !in names)

            val before = file.text()
            val night = emptyResources()
            val stateLists = mutableMapOf<String, String>()
            restyled += writeNightStyles(family, decoded.colours, decoded.nightColours, tokens, night, emptyResources(),
                emptyResources(), stateLists, plain)
            assertEquals("$build: the default $type file changed", before, file.text())

            for (copy in night.documentElement.elements()) {
                val name = copy.getAttribute("name")
                assertTrue("$build: $name is no dark style", name in names)
                for (item in copy.elements()) {
                    if (!item.textContent.startsWith("@color/hushfacebook_you_")) continue
                    val attribute = item.getAttribute("name")
                    val token = tokens[attribute] ?: error("$build: $name changes $attribute, no FDS token")
                    val colour = decoded.styleColours.getValue(name)[attribute]
                    assertTrue("$build: $name changes $token, ${colour?.let { hex(setOf(it)) }}, which no table lists",
                        colour != null && colour in listed[token].orEmpty())
                    val stateList = item.textContent.removePrefix("@color/") in stateLists
                    assertEquals("$build: $name gives $token ${item.textContent}, a state list is $stateList", token !in plain, stateList)
                    if (token in plain) plainTones += "$token ${item.textContent}"
                    if (name == family.first().getAttribute("name")) darkTokens += token
                }
            }
        }
        assertTrue("$build: no night style item takes the palette", restyled > 0)
        assertTrue("$build: the dark style keeps its PRIMARY_BUTTON_BACKGROUND, found $darkTokens",
            "PRIMARY_BUTTON_BACKGROUND" in darkTokens)
        assertTrue("$build: PRIMARY_TEXT takes no system tone, found $plainTones",
            "PRIMARY_TEXT @color/hushfacebook_you_neutral_95" in plainTones)
    }

    /**
     * The tokens the patch's styleable scan reaches over every class in [apk]: the attributes some
     * styleable array reads as an integer, followed through [styles]' `?attr/` aliases to [tokens].
     * The patch walks only the classes the reference index hands it, which has to find the same.
     */
    private fun styleableTokens(apk: File, version: String, styles: List<Document>, tokens: Map<String, String>): Set<String> {
        val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
        val scan = integerReadAttributes({ visit -> classes.forEach(visit) })
        assertTrue("${apk.name}: no styleable array is read as an integer", scan.attributes.isNotEmpty())
        val context = PatchContexts.of(classes)
        var visited = 0
        val indexed = integerReadAttributes({ visit -> classes.forEach(visit) }) { types, visit ->
            context.classDefForEachReferencing(types = types) { visited++; visit(it) }
        }
        assertTrue("${apk.name}: the indexed walk reached $visited of ${classes.size} classes", visited < classes.size / 5)
        assertEquals("${apk.name}: the indexed walk's attributes", scan.attributes, indexed.attributes)
        assertEquals("${apk.name}: the indexed walk's readers", scan.readers, indexed.readers)
        assertEquals("${apk.name}: the indexed walk's arrays",
            scan.arrays.mapValues { it.value.toList() }, indexed.arrays.mapValues { it.value.toList() })
        val likesLine = LIKES_LINE_ATTRIBUTE.getValue(version)
        assertTrue("${apk.name}: the likes line's text colour attribute isn't read as an integer", likesLine in scan.attributes)
        val readers = scan.readers.filterValues { fields -> fields.any { likesLine in scan.arrays.getValue(it) } }
        assertTrue("${apk.name}: the reactions pill bar doesn't read the likes line's array, found ${readers.keys}",
            PILLS_BLING_BAR in readers)
        assertTrue("${apk.name}: only the pill bar reads the likes line's array", readers.size > 1)
        assertEquals("${apk.name}: the likes line's attribute alone reaches its token",
            setOf("SECONDARY_TEXT"), styleablePlainTokens(styles, tokens, setOf(likesLine)))
        return styleablePlainTokens(styles, tokens, scan.attributes)
    }

    /** What the patch's scan for tokens read through `TypedValue.data` finds over every class in [apk]. */
    private fun scannedTokens(apk: File, initializer: Method, tokenType: String): DataReadScan {
        val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        val forEachClass: ((ClassDef) -> Unit) -> Unit = { visit ->
            for (name in container.dexEntryNames) container.getEntry(name)!!.dexFile.classes.forEach(visit)
        }
        var tokenClass: ClassDef? = null
        forEachClass { if (it.type == tokenType && tokenClass == null) tokenClass = it }
        return dataReadTokens(forEachClass, tokenConstants(initializer, tokenType), tokenAttributeField(checkNotNull(tokenClass)))
    }

    /**
     * One build's default colours and style files, as the resource decoder writes them for names
     * Facebook strips: `color_0x7f0601d5`, `attr_0x7f0405bd`, `style.2_0x7f200229`. Style items keep
     * a colour or a colour reference and write anything else as `@null`, which no route reads.
     */
    private class Decoded(
        val colours: Map<String, String>,
        val nightColours: Set<String>,
        val styleFiles: Map<String, Document>,
        val styleColours: Map<String, Map<String, Int>>,
        /** The colours any configuration other than the default gives a value of their own. */
        val configuredColours: Set<String>,
    )

    private fun decode(apk: File): Decoded {
        val table = ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry(TableBlock.FILE_NAME)).use { TableBlock.load(it) } }
        val byId = mutableMapOf<Int, ResourceEntry>()
        for (block in table.listPackages()) {
            for (pair in block.listSpecTypePairs()) {
                for (resource in pair.resources) if (resource != null && !resource.isEmpty) byId[resource.resourceId] = resource
            }
        }
        fun name(resource: ResourceEntry) = "${resource.type}_0x%08x".format(resource.resourceId)
        fun text(item: ValueItem): String = when (item.valueType) {
            ValueType.COLOR_ARGB8, ValueType.COLOR_RGB8, ValueType.COLOR_ARGB4, ValueType.COLOR_RGB4 -> "#%08x".format(item.data)
            ValueType.REFERENCE, ValueType.DYNAMIC_REFERENCE ->
                byId[item.data]?.takeIf { it.type == "color" }?.let { "@color/${name(it)}" } ?: "@null"
            ValueType.ATTRIBUTE, ValueType.DYNAMIC_ATTRIBUTE -> "?attr/attr_0x%08x".format(item.data)
            else -> "@null"
        }

        val colours = mutableMapOf<String, String>()
        val nightColours = mutableSetOf<String>()
        val styleFiles = mutableMapOf<String, Document>()
        val styleColours = mutableMapOf<String, Map<String, Int>>()
        val configuredColours = mutableSetOf<String>()
        for (resource in byId.values.sortedBy { it.resourceId }) {
            val entries = resource.iterator().asSequence().filterNotNull().filter { !it.isNull }.toList()
            if (resource.type == "color") {
                if (entries.any { it.resConfig.uiModeNight == ResConfig.UiModeNight.NIGHT }) nightColours += name(resource)
                if (entries.any { !it.resConfig.isDefault }) configuredColours += name(resource)
                entries.firstOrNull { it.resConfig.isDefault && !it.isComplex }?.let { colours[name(resource)] = text(it.resValue) }
            } else if (resource.type.startsWith("style")) {
                val bag = entries.firstOrNull { it.resConfig.isDefault && it.isComplex }?.tableEntry as? ResTableMapEntry ?: continue
                val file = styleFiles.getOrPut(resource.type) { emptyResources() }
                val style = file.createElement(resource.type)
                style.setAttribute("name", name(resource))
                if (bag.parentId != 0) {
                    style.setAttribute("parent", byId[bag.parentId]?.let { "@${it.type}/${name(it)}" } ?: "@android:style/Theme")
                }
                val values = mutableMapOf<String, Int>()
                for (item in bag) {
                    val attribute = "attr_0x%08x".format(item.nameId)
                    style.appendChild(file.createElement("item").also {
                        it.setAttribute("name", attribute)
                        it.textContent = text(item)
                    })
                    colour(item, byId, 0)?.let { values[attribute] = it }
                }
                file.documentElement.appendChild(style)
                styleColours[name(resource)] = values
            }
        }
        return Decoded(colours, nightColours, styleFiles, styleColours, configuredColours)
    }

    private fun emptyResources(): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument().also { it.appendChild(it.createElement("resources")) }

    private fun Element.elements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Document.text(): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(this), StreamResult(it))
    }.toString()

    private class Style(val id: Int, val parent: Int, val sets: Int, val values: Map<Int, Int>)

    /** Every style that sets 20 or more FDS attributes, with each one's colour where it resolves to one. */
    private fun fdsStyles(apk: File, fdsAttributes: Set<Int>): Map<Int, Style> {
        val byId = resources(apk)
        val styles = mutableMapOf<Int, Style>()
        for (resource in byId.values) {
            if (!resource.type.startsWith("style")) continue
            for (entry in resource.iterator()) {
                if (entry == null || entry.isNull || !entry.isComplex) continue
                val bag = entry.tableEntry as ResTableMapEntry
                val values = mutableMapOf<Int, Int>()
                var set = 0
                for (item in bag) {
                    if (item.nameId !in fdsAttributes) continue
                    set++
                    colour(item, byId, 0)?.let { values[item.nameId] = it }
                }
                if (set >= 20) styles[resource.resourceId] = Style(resource.resourceId, bag.parentId, set, values)
            }
        }
        return styles
    }

    /** A colour value, following references to colour resources in their default configuration. */
    private fun colour(item: ValueItem, byId: Map<Int, ResourceEntry>, depth: Int): Int? = when (item.valueType) {
        ValueType.COLOR_ARGB8, ValueType.COLOR_RGB8, ValueType.COLOR_ARGB4, ValueType.COLOR_RGB4 -> item.data
        ValueType.REFERENCE, ValueType.DYNAMIC_REFERENCE -> {
            val target = byId[item.data]
            val entry: Entry? = target?.let { resource ->
                resource.iterator().asSequence().firstOrNull { it != null && !it.isNull && it.resConfig.isDefault }
            }
            if (entry == null || entry.isComplex || depth > 4) null else colour(entry.resValue, byId, depth + 1)
        }
        else -> null
    }

    /** The static initializer of the token enum FDSColors resolves, and the enum's type. */
    private fun tokenInitializer(apk: File): Pair<Method, String> {
        val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        val classes = mutableMapOf<String, ClassDef>()
        for (name in container.dexEntryNames) {
            for (classDef in container.getEntry(name)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
        }
        val source = classes.values.mapNotNull { fdsColourSource(it) }.single()
        val tokenType = source.parameterTypes[1].toString()
        return classes.getValue(tokenType).methods.single { it.name == "<clinit>" } to tokenType
    }

    /** Each constant of the token enum FDSColors resolves, by name, with the theme attribute it passes. */
    private fun tokenAttributes(apk: File): Map<String, Int> {
        val (initializer, tokenType) = tokenInitializer(apk)

        // Follows constants and moves through registers to each constructor call, which takes the
        // name, the ordinal, the theme attribute, a fallback colour and a colour resource.
        val registers = mutableMapOf<Int, Any?>()
        val tokens = mutableMapOf<String, Int>()
        for (instruction in initializer.implementation!!.instructions) {
            val opcode = instruction.opcode
            when {
                instruction is NarrowLiteralInstruction && instruction is OneRegisterInstruction &&
                    opcode in setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16) ->
                    registers[instruction.registerA] = instruction.narrowLiteral
                opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO ->
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        ((instruction as ReferenceInstruction).reference as StringReference).string
                opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_OBJECT,
                    Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) ->
                    registers[(instruction as TwoRegisterInstruction).registerA] = registers[instruction.registerB]
                (opcode == Opcode.INVOKE_DIRECT || opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                    ((instruction as ReferenceInstruction).reference as? MethodReference)
                        ?.let { it.name == "<init>" && it.definingClass == tokenType } == true -> {
                    val arguments = when (instruction) {
                        is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
                        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD,
                            instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
                        else -> error("unexpected call form $opcode")
                    }
                    val name = registers[arguments[1]] as? String ?: error("a token constructed without a name")
                    tokens[name] = registers[arguments[3]] as? Int ?: error("$name has no theme attribute")
                }
                instruction is OneRegisterInstruction && opcode != Opcode.SPUT_OBJECT -> registers.remove(instruction.registerA)
            }
        }
        return tokens
    }

    private fun withBaseApk(fixture: File, check: (File) -> Unit) = ZipFile(fixture).use { zip ->
        val base = checkNotNull(zip.getEntry("base.apk")) { "${fixture.name} holds no base.apk" }
        val copy = File.createTempFile("fixture-base", ".apk")
        try {
            zip.getInputStream(base).use { input -> copy.outputStream().use { input.copyTo(it) } }
            check(copy)
        } finally {
            copy.delete()
        }
    }

    private fun hex(colours: Set<Int>) = colours.joinToString(",") { "#%08X".format(it) }

    /** Every build the bundle pins, one per ABI of each declared version, each a fixture of its own. */
    private fun declaredBuilds() = AppCompatibilities.facebook().single().targets.sumOf { it.versionCodes.orEmpty().size }
}
