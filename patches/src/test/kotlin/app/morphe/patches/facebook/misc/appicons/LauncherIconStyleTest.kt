/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hushfacebook settings' Icon style option (#278) on a hand-written copy of 584's launcher icon,
 * and on the icon and themed-icon vector every declared Facebook build decodes to. The fixture
 * half reads the bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without them.
 */
class LauncherIconStyleTest {
    private fun xml(text: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())

    /** 584's res/mipmap-anydpi/icon_katana.xml as it decodes. */
    private fun icon() = xml(
        """<?xml version="1.0" encoding="utf-8"?>
        <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            <background android:drawable="@mipmap/icon_katana_background"/>
            <foreground android:drawable="@mipmap/icon_katana_foreground"/>
            <monochrome android:drawable="@drawable/icon_katana_monochrome"/>
        </adaptive-icon>""",
    )

    /** 584's themed-icon vector, its long path cut short. */
    private fun glyph() = xml(
        """<?xml version="1.0" encoding="utf-8"?>
        <vector xmlns:android="http://schemas.android.com/apk/res/android" android:height="108dp"
            android:width="108dp" android:viewportWidth="108" android:viewportHeight="108">
            <path android:fillColor="#ffffffff" android:pathData="M58.3632 89.18H45.3024V65.0015Z" android:fillAlpha="1"/>
        </vector>""",
    )

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Document.layer(tag: String) = documentElement.children().single { it.tagName == tag }

    private fun assertRestyled(icon: Document, style: LauncherIconStyle, where: String = "") {
        val background = icon.layer("background")
        assertEquals("$where background names a drawable", "", background.getAttribute("android:drawable"))
        val color = background.children().single()
        assertEquals("$where background", "color", color.tagName)
        assertEquals("$where background color", style.background, color.getAttribute("android:color"))

        val foreground = icon.layer("foreground")
        assertEquals("$where foreground names a drawable", "", foreground.getAttribute("android:drawable"))
        val vector = foreground.children().single()
        assertEquals("$where foreground", "vector", vector.tagName)
        assertEquals("$where vector size", "108", vector.getAttribute("android:viewportWidth").substringBefore('.'))
        val paths = vector.getElementsByTagName("path").let { list -> (0 until list.length).map { list.item(it) as Element } }
        assertTrue("$where vector has no path", paths.isNotEmpty())
        paths.forEach { assertEquals("$where path fill", style.glyph, it.getAttribute("android:fillColor")) }
        assertTrue("$where path lost its shape", paths.all { it.getAttribute("android:pathData").startsWith("M") })

        // Themed icons still read the untouched monochrome layer.
        assertEquals("$where monochrome", "@drawable/icon_katana_monochrome", icon.layer("monochrome").getAttribute("android:drawable"))
    }

    /** Every point a path's lines and arcs end on, as x to y. */
    private fun points(pathData: String): List<Pair<Double, Double>> =
        Regex("""(?:[ML]|A[\d.]+,[\d.]+ 0 0 1 )([\d.]+),([\d.]+)""").findAll(pathData)
            .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()

    private fun Element.paths() = getElementsByTagName("path").let { list -> (0 until list.length).map { list.item(it) as Element } }

    /** Hushfacebook's logo in every layer, all of it inside the 66 dp circle no launcher mask cuts. */
    private fun assertHushfacebook(icon: Document, where: String = "") {
        val background = icon.layer("background")
        assertEquals("$where background names a drawable", "", background.getAttribute("android:drawable"))
        assertEquals("$where background color", "#ff1371f1", background.children().single().getAttribute("android:color"))

        val foreground = icon.layer("foreground")
        assertEquals("$where foreground names a drawable", "", foreground.getAttribute("android:drawable"))
        val paths = foreground.children().single().paths()
        // Navy copy, light blue copy, the white H, then its slots in the background's blue.
        assertEquals(
            "$where foreground colors, back to front",
            listOf("#ff05255b", "#ff3c92fc", "#fff6f9fd", "#ff1371f1"),
            paths.map { it.getAttribute("android:fillColor") },
        )

        val monochrome = icon.layer("monochrome")
        assertEquals("$where monochrome names a drawable", "", monochrome.getAttribute("android:drawable"))
        val shape = monochrome.children().single().paths().single()
        assertEquals("$where the H's slots aren't cut out", "evenOdd", shape.getAttribute("android:fillType"))
        // The H's outline and its two slots.
        assertEquals("$where monochrome shapes", 3, shape.getAttribute("android:pathData").count { it == 'M' })

        for (path in paths + shape) {
            val corners = points(path.getAttribute("android:pathData"))
            assertTrue("$where a path has no points", corners.size >= 8)
            corners.forEach { (x, y) ->
                assertTrue("$where ($x, $y) is outside the safe circle", Math.hypot(x - 54, y - 54) <= 33.0)
            }
        }
    }

    @Test
    fun `Facebook colors and an empty option keep the icon`() {
        assertNull(pickedIconStyle(null))
        assertNull(pickedIconStyle(" "))
        assertNull(pickedIconStyle("facebook"))
        assertNull(pickedIconStyle("Facebook colors"))
        assertEquals(LauncherIconStyle.WHITE_ON_BLACK, pickedIconStyle("white-on-black"))
        assertEquals(LauncherIconStyle.BLACK_ON_WHITE, pickedIconStyle(" Black on white "))
        assertEquals(LauncherIconStyle.HUSHFACEBOOK, pickedIconStyle("hushfacebook"))
        assertEquals(LauncherIconStyle.HUSHFACEBOOK, pickedIconStyle("Hushfacebook"))
        assertThrows(PatchException::class.java) { pickedIconStyle("blue") }
    }

    @Test
    fun `the Hushfacebook style draws the H on Hushfacebook blue and themes it too`() {
        val icon = icon()
        restyleLauncherIcon(icon, glyph(), LauncherIconStyle.HUSHFACEBOOK)
        assertHushfacebook(icon)
    }

    @Test
    fun `the Hushfacebook style adds a themed layer to an icon without one`() {
        val icon = xml(
            """<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@mipmap/icon_katana_background"/>
                <foreground android:drawable="@mipmap/icon_katana_foreground"/>
            </adaptive-icon>""",
        )
        drawHushfacebookIcon(icon)
        assertHushfacebook(icon)
    }

    @Test
    fun `the Hushfacebook style refuses a plain picture untouched`() {
        val icon = xml("""<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@mipmap/icon_katana_foreground"/>""")
        assertThrows(PatchException::class.java) { drawHushfacebookIcon(icon) }
        assertEquals("@mipmap/icon_katana_foreground", icon.documentElement.getAttribute("android:src"))
        assertTrue(icon.documentElement.children().isEmpty())
    }

    @Test
    fun `each plain style draws the themed f in one color on a solid background`() {
        for (style in listOf(LauncherIconStyle.WHITE_ON_BLACK, LauncherIconStyle.BLACK_ON_WHITE)) {
            val icon = icon()
            restyleLauncherIcon(icon, glyph(), style)
            assertRestyled(icon, style, style.key)
        }
    }

    @Test
    fun `an icon without a themed layer is refused untouched`() {
        val icon = xml(
            """<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@mipmap/icon_katana_background"/>
                <foreground android:drawable="@mipmap/icon_katana_foreground"/>
            </adaptive-icon>""",
        )
        assertThrows(PatchException::class.java) { restyleLauncherIcon(icon, glyph(), LauncherIconStyle.WHITE_ON_BLACK) }
        assertEquals("@mipmap/icon_katana_background", icon.layer("background").getAttribute("android:drawable"))
        assertEquals("@mipmap/icon_katana_foreground", icon.layer("foreground").getAttribute("android:drawable"))
    }

    @Test
    fun `a themed layer that isn't a vector is refused untouched`() {
        val icon = icon()
        val bitmap = xml("""<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/x"/>""")
        assertThrows(PatchException::class.java) { restyleLauncherIcon(icon, bitmap, LauncherIconStyle.BLACK_ON_WHITE) }
        assertEquals("@mipmap/icon_katana_foreground", icon.layer("foreground").getAttribute("android:drawable"))
    }

    @Test
    fun `a separate round icon is refused`() {
        val manifest = xml(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:icon="@mipmap/icon_katana" android:roundIcon="@mipmap/icon_round"/>
            </manifest>""",
        )
        assertThrows(PatchException::class.java) { applicationIcon(manifest) }
        assertEquals("mipmap" to "icon_katana", appResource("@com.facebook.katana:mipmap/icon_katana"))
        assertNull(appResource("@android:drawable/sym_def_app_icon"))
    }

    // ------------------------------------------------------------------ the declared builds

    /** Every decoded resource path of [bundle]'s base APK and a reader for them, decoded by ARSCLib, Morphe's resource library. */
    private fun withDecoded(bundle: File, check: (List<String>, (String) -> Document, Document) -> Unit) {
        val work = Files.createTempDirectory("hushfacebook-icon").toFile()
        try {
            val apk = File(work, "base.apk")
            ZipFile(bundle).use { zip ->
                val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
                zip.getInputStream(base).use { input -> apk.outputStream().use { input.copyTo(it) } }
            }
            val decoded = File(work, "decoded")
            val module = ApkModule.loadApkFile(apk)
            // Only the launcher icon's names, so a test run doesn't decode thousands of layouts.
            val wanted = Regex("""res/(mipmap|drawable)[^/]*/icon_katana[^/]*\.xml""")
            val xmls = try {
                ApkModuleXmlDecoder(module).decodeAndroidManifest(decoded)
                val pkg = module.tableBlock.getPackageBlockById(0x7f)
                module.listResFiles().filter { wanted.matches(it.buildPath()) }.associate { file ->
                    val document = file.readAsXmlDocument()
                    document.setPackageBlock(pkg)
                    file.buildPath() to document.serializeToXml()
                }
            } finally {
                module.close()
            }
            val read = { path: String -> xml(xmls.getValue(path)) }
            val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(decoded.walk().single { it.name == "AndroidManifest.xml" })
            check(xmls.keys.toList(), read, manifest)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `each declared build's icon restyles from its own themed vector`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withDecoded(bundle) { paths, read, manifest ->
                    val name = bundle.name
                    val icon = checkNotNull(appResource(applicationIcon(manifest))) { "$name: icon" }
                    assertEquals("$name: the app icon", "mipmap" to "icon_katana", icon)
                    // The PNG configurations decode to no XML, so the one adaptive icon must be found among the rest.
                    val iconPath = singleXml(paths, icon, "adaptive-icon", read)
                    val glyph = monochromeReference(read(iconPath))
                    assertEquals("$name: the themed-icon layer", "drawable" to "icon_katana_monochrome", glyph)
                    val glyphPath = singleXml(paths, glyph, "vector", read)
                    val document = read(iconPath)
                    restyleLauncherIcon(document, read(glyphPath), LauncherIconStyle.BLACK_ON_WHITE)
                    assertRestyled(document, LauncherIconStyle.BLACK_ON_WHITE, name)
                    val hush = read(iconPath)
                    restyleLauncherIcon(hush, read(glyphPath), LauncherIconStyle.HUSHFACEBOOK)
                    assertHushfacebook(hush, name)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
