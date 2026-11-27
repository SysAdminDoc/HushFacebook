/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Ported from SysAdminDoc/HushGram's AppIconsTest.kt (GPL-3.0).
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.ExtensionDex
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Pick the app icon (#467) on a hand-written copy of 588's launcher entries and icon: an alias per
 * Hushfacebook icon right after Facebook's launcher alias, each a copy of it that's off and carries
 * Facebook's default-state meta-data, an adaptive icon per alias drawn the way its Icon style draws
 * it, and the names the extension turns on.
 */
class PickAppIconTest {
    private val target = "com.facebook.katana.activity.FbMainTabActivity"

    /** 588's alternate icon and launcher alias as the manifest decodes, the screens around them cut. */
    private val manifest = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.facebook.katana">
            <application android:icon="@mipmap/icon_katana">
                <activity android:name="$target" />
                <activity-alias android:icon="@mipmap/icon_katana" android:name="com.facebook.katana.LoginActivity.blue_fb" android:enabled="false" android:exported="true" android:targetActivity="$target">
                    <meta-data android:name="default-state" android:value="false" />
                    <meta-data android:name="enable-stage" android:value="enable-cold-pretos" />
                    <meta-data android:name="crash.loop.exclude" android:value="true" />
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity-alias>
                <activity-alias android:icon="@mipmap/icon_katana" android:name="com.facebook.katana.LoginActivity" android:enabled="true" android:exported="true" android:targetActivity="$target">
                    <meta-data android:name="enable-stage" android:value="enable-cold-pretos" />
                    <meta-data android:name="crash.loop.exclude" android:value="true" />
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity-alias>
                <activity android:name="com.facebook.katana.immersiveactivity.ImmersiveActivity" />
            </application>
        </manifest>
    """

    /** 584's res/mipmap-anydpi/icon_katana.xml as it decodes. */
    private val icon = """<?xml version="1.0" encoding="utf-8"?>
        <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            <background android:drawable="@mipmap/icon_katana_background"/>
            <foreground android:drawable="@mipmap/icon_katana_foreground"/>
            <monochrome android:drawable="@drawable/icon_katana_monochrome"/>
        </adaptive-icon>"""

    /** 584's themed-icon vector, its long path cut short. */
    private val glyph = """<?xml version="1.0" encoding="utf-8"?>
        <vector xmlns:android="http://schemas.android.com/apk/res/android" android:height="108dp"
            android:width="108dp" android:viewportWidth="108" android:viewportHeight="108">
            <path android:fillColor="#ffffffff" android:pathData="M58.3632 89.18H45.3024V65.0015Z" android:fillAlpha="1"/>
        </vector>"""

    @Test
    fun eachIconIsACopyOfFacebooksAliasThatsOff() {
        val document = parse(manifest)
        addIconAliases(document)
        val aliases = document.elements("activity-alias")
        assertEquals(
            "right after Facebook's, in order",
            listOf("com.facebook.katana.LoginActivity.blue_fb", FACEBOOK_ALIAS) + HUSHFACEBOOK_ICONS.map { it.alias },
            aliases.map { it.getAttribute("android:name") },
        )
        assertEquals("the screen after them stays after them", "com.facebook.katana.immersiveactivity.ImmersiveActivity",
            document.elements("activity").last().getAttribute("android:name"))
        val facebook = aliases[1]
        assertEquals("Facebook's is left as it was", "true", facebook.getAttribute("android:enabled"))
        assertEquals(listOf("enable-stage", "crash.loop.exclude"), facebook.elements("meta-data").map { it.getAttribute("android:name") })
        for ((icon, alias) in HUSHFACEBOOK_ICONS.zip(aliases.drop(2))) {
            assertEquals(icon.key, "false", alias.getAttribute("android:enabled"))
            assertEquals(icon.key, "@mipmap/hushfacebook_icon_${icon.key}", alias.getAttribute("android:icon"))
            assertEquals(icon.key, "", alias.getAttribute("android:roundIcon"))
            assertEquals(icon.key, target, alias.getAttribute("android:targetActivity"))
            assertEquals(icon.key, "true", alias.getAttribute("android:exported"))
            assertEquals(
                icon.key,
                listOf("default-state" to "false", "enable-stage" to "enable-cold-pretos", "crash.loop.exclude" to "true"),
                alias.elements("meta-data").map { it.getAttribute("android:name") to it.getAttribute("android:value") },
            )
            val filter = alias.elements("intent-filter").single()
            assertEquals(icon.key, "android.intent.category.LAUNCHER", filter.elements("category").single().getAttribute("android:name"))
        }
    }

    @Test
    fun aRoundIconAndAShortNameAreReadToo() {
        val round = parse(manifest.replace("android:enabled=\"true\"", "android:enabled=\"true\" android:roundIcon=\"@mipmap/icon_round\""))
        addIconAliases(round)
        val alias = round.elements("activity-alias").single { it.getAttribute("android:name") == HUSHFACEBOOK_ICONS[1].alias }
        assertEquals("@mipmap/hushfacebook_icon_hushfacebook", alias.getAttribute("android:roundIcon"))

        val short = parse(manifest.replace("android:name=\"com.facebook.katana.LoginActivity\"", "android:name=\".LoginActivity\""))
        addIconAliases(short)
        assertEquals(HUSHFACEBOOK_ICONS.size + 2, short.elements("activity-alias").size)
    }

    @Test
    fun withoutFacebooksAliasOrWithHushfacebooksAlreadyThereItRefuses() {
        refuses("found 0", manifest.replace("android:name=\"com.facebook.katana.LoginActivity\"", "android:name=\"com.facebook.katana.Other\""))
        refuses("found 2", manifest.replace(".LoginActivity.blue_fb\"", ".LoginActivity\""))
        val twice = parse(manifest).also(::addIconAliases)
        val again = assertThrows(PatchException::class.java) { addIconAliases(twice) }
        assertTrue(again.message, again.message.orEmpty().contains("already has ${HUSHFACEBOOK_ICONS[0].alias}"))
        assertTrue(again.message, again.message.orEmpty().startsWith("$ICON_PATCH: "))
    }

    /** Each icon is Facebook's own icon restyled by its Icon style, and the Facebook one is Facebook's own. */
    @Test
    fun eachIconIsDrawnTheWayItsIconStyleDrawsIt() {
        val files = iconFiles(icon, parse(glyph))
        assertEquals(HUSHFACEBOOK_ICONS.map { it.mipmap }, files.keys.toList())
        for (each in HUSHFACEBOOK_ICONS) {
            val drawn = parse(icon).also { restyleLauncherIcon(it, parse(glyph), each.style) }
            assertEquals(each.key, text(drawn), text(parse(files.getValue(each.mipmap))))
        }
        fun layers(key: String) = parse(files.getValue("hushfacebook_icon_$key")).let { file ->
            listOf("background", "foreground", "monochrome").map { file.elements(it).single() }
        }
        assertEquals("Facebook's own layers", listOf("@mipmap/icon_katana_background", "@mipmap/icon_katana_foreground",
            "@drawable/icon_katana_monochrome"), layers("facebook").map { it.getAttribute("android:drawable") })
        val white = layers("white_on_black")
        assertEquals("#ff000000", white[0].elements("color").single().getAttribute("android:color"))
        assertEquals(setOf("#ffffffff"), white[1].elements("path").map { it.getAttribute("android:fillColor") }.toSet())
        val black = layers("black_on_white")
        assertEquals("#ffffffff", black[0].elements("color").single().getAttribute("android:color"))
        assertEquals(setOf("#ff000000"), black[1].elements("path").map { it.getAttribute("android:fillColor") }.toSet())
        val logo = layers("hushfacebook")
        assertEquals(LauncherIconStyle.HUSHFACEBOOK.background, logo[0].elements("color").single().getAttribute("android:color"))
        assertTrue("the H is drawn in", logo[1].elements("path").isNotEmpty())
    }

    @Test
    fun anIconThatCantBeDrawnIsRefusedByName() {
        val flat = icon.replace(Regex("""\s*<foreground[^>]*/>"""), "")
        val refusal = assertThrows(PatchException::class.java) { iconFiles(flat, parse(glyph)) }
        assertTrue(refusal.message, refusal.message.orEmpty().startsWith("$ICON_PATCH: the "))
        assertTrue(refusal.message, refusal.message.orEmpty().contains("no foreground layer"))
    }

    /** The Facebook icon copies the launcher icon as it was before this run's Icon style drew over it. */
    @Test
    fun theFacebookIconIsTheOneFromBeforeIconStyle() {
        try {
            PristineLauncherIcon.clear()
            assertEquals("the file as it is", "now", PristineLauncherIcon.before("res/mipmap-anydpi/icon_katana.xml") { "now" })
            PristineLauncherIcon.remember("res/mipmap-anydpi/icon_katana.xml", "before")
            assertEquals("before", PristineLauncherIcon.before("res/mipmap-anydpi/icon_katana.xml") { "now" })
            assertEquals("another file's copy isn't it", "now", PristineLauncherIcon.before("res/mipmap-anydpi-v26/icon_katana.xml") { "now" })
            PristineLauncherIcon.clear()
            assertEquals("an older run's copy is gone", "now", PristineLauncherIcon.before("res/mipmap-anydpi/icon_katana.xml") { "now" })
        } finally {
            PristineLauncherIcon.clear()
        }
    }

    /** The aliases the patch adds are the ones the extension's picks turn on, and every stand-in is there. */
    @Test
    fun thePatchAndTheExtensionNameTheSameIcons() {
        val appIcon = componentStandIn(COMPONENT_CALLS.keys.first()).substringBefore("->")
        assertEquals(HUSHFACEBOOK_ALIAS, ExtensionDex.stringConstant(appIcon, "HUSHFACEBOOK_ALIAS"))
        assertEquals(FACEBOOK_ALIAS, ExtensionDex.stringConstant("Lapp/morphe/extension/facebook/misc/AppIcons;", "LAUNCHER"))

        val choice = ExtensionDex.classDef("Lapp/morphe/extension/facebook/misc/AppIconChoice;")
        val picks = choice.staticFields.filter { AccessFlags.ENUM.isSet(it.accessFlags) }.map { it.name }
        assertEquals("one pick per icon and the patched one", HUSHFACEBOOK_ICONS.size + 1, picks.size)
        val written = choice.methods.single { it.name == "<clinit>" }.implementation!!.instructions
            .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
        for (each in HUSHFACEBOOK_ICONS) {
            assertTrue("${each.key} is no pick's key", each.key in written)
            assertTrue("${each.style.name} is no pick", each.style.name in picks)
        }

        val declared = ExtensionDex.classDef(appIcon).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (name in COMPONENT_CALLS.keys) assertTrue("${componentStandIn(name)} is not in $declared", componentStandIn(name) in declared)
    }

    private fun refuses(reason: String, xml: String) {
        val document = parse(xml)
        val refusal = assertThrows(PatchException::class.java) { addIconAliases(document) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        assertTrue("an alias went in", document.elements("activity-alias").none { it.getAttribute("android:name").startsWith(HUSHFACEBOOK_ALIAS) })
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.trimIndent().byteInputStream())

    private fun text(document: Document): String {
        val out = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(out))
        return out.toString()
    }

    private fun Document.elements(tag: String): List<Element> = documentElement.elements(tag).let {
        if (documentElement.tagName == tag) listOf(documentElement) + it else it
    }

    private fun Element.elements(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }
}
