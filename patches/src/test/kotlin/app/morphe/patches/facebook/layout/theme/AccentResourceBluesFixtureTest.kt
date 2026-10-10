/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ResTableMapEntry
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document

/**
 * Route five's table on every declared Facebook build, from its token enum and its resource table
 * written the way the resource decoder writes them. It has to hold the two blues seen staying
 * Facebook's on the 582 profile (2026-10-10): the bio link's #0064D1 behind BLUE_LINK, which
 * textColorLink points at, and the Add to story button's #0866FF behind PRIMARY_BUTTON_BACKGROUND.
 */
class AccentResourceBluesFixtureTest {
    @Test
    fun `each declared build's table holds the bio link's and the button's blues`() {
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
        val fdsColors = FixtureDex.classes(bundle, setOf(FDS_COLORS)).getValue(FDS_COLORS)
        val source = fdsColors.methods.single { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/Integer;" &&
                method.parameterTypes.size == 3 && method.parameterTypes[0].toString() == "Landroid/content/Context;"
        }
        val tokenType = source.parameterTypes[1].toString()
        val initializer = FixtureDex.classes(bundle, setOf(tokenType)).getValue(tokenType).methods.single { it.name == "<clinit>" }
        val tokens = tokenAttributes(initializer, tokenType).entries.associate { (token, attribute) -> "attr_0x%08x".format(attribute) to token }

        val decoded = decode(bundle)
        val table = resourceBluesTable(decoded.styles, decoded.colours, decoded.nightColours, emptySet(), tokens, ::decodedColourId)
        val entries = table.split(";").associate { entry ->
            val (colours, forTokens) = entry.substringAfter('=').split(":")
            entry.substringBefore('=') to (colours to forTokens.split(",").toSet())
        }

        fun heldFor(token: String, colour: String) = entries.values.any { (colours, forTokens) ->
            token in forTokens && colours.substringBefore('/') == colour
        }
        assertTrue("$name: no #0064D1 behind BLUE_LINK in $table", heldFor("BLUE_LINK", "ff0064d1"))
        assertTrue("$name: no #0866FF behind PRIMARY_BUTTON_BACKGROUND in $table", heldFor("PRIMARY_BUTTON_BACKGROUND", "ff0866ff"))
        // A table this size is a few kilobytes in the extension's dex, not the whole palette.
        assertTrue("$name: ${entries.size} colour resources", entries.size in 2..64)
    }

    private class Decoded(val colours: Map<String, String>, val nightColours: Map<String, String>, val styles: List<Document>)

    /**
     * The default and night colours and the default styles of the bundle's base APK, as the
     * resource decoder writes them for names Facebook strips: `color_0x7f0601d5`, `attr_0x7f0405bd`.
     * A style item keeps a colour or a colour reference and is `@null` otherwise.
     */
    private fun decode(bundle: File): Decoded {
        val table = ZipFile(bundle).use { zip ->
            ZipInputStream(zip.getInputStream(checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" })).use { apk ->
                generateSequence { apk.nextEntry }.first { it.name == TableBlock.FILE_NAME }
                TableBlock.load(apk)
            }
        }
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
            else -> "@null"
        }

        val colours = mutableMapOf<String, String>()
        val nightColours = mutableMapOf<String, String>()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val resources = document.createElement("resources").also { document.appendChild(it) }
        for (resource in byId.values.sortedBy { it.resourceId }) {
            val entries = resource.iterator().asSequence().filterNotNull().filter { !it.isNull }.toList()
            if (resource.type == "color") {
                entries.firstOrNull { it.resConfig.isDefault && !it.isComplex }?.let { colours[name(resource)] = text(it.resValue) }
                entries.firstOrNull { it.resConfig.qualifiers.trim('-') in setOf("night", "night-v8") && !it.isComplex }
                    ?.let { nightColours[name(resource)] = text(it.resValue) }
            } else if (resource.type.startsWith("style")) {
                val bag = entries.firstOrNull { it.resConfig.isDefault && it.isComplex }?.tableEntry as? ResTableMapEntry ?: continue
                val style = document.createElement("style").also { it.setAttribute("name", name(resource)) }
                for (item in bag) {
                    style.appendChild(document.createElement("item").also {
                        it.setAttribute("name", "attr_0x%08x".format(item.nameId))
                        it.textContent = text(item)
                    })
                }
                resources.appendChild(style)
            }
        }
        return Decoded(colours, nightColours, listOf(document))
    }
}
