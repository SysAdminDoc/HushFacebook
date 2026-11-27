/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Ported from SysAdminDoc/HushGram's PickAppIconFixtureTest.kt (GPL-3.0).
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.ads.audiencenetwork.REMOTE_APIS_SETUP
import app.morphe.patches.facebook.ads.audiencenetwork.componentStateCalls
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import org.junit.Assert.assertEquals
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
 * Pick the app icon (#467) on every Facebook build the bundle declares: the manifest takes the
 * aliases right after Facebook's launcher alias, each icon draws from the build's own launcher icon,
 * and every call Facebook makes to turn a component on or off is one the patch can move, which after
 * the patch goes to AppIcon on the same registers. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without them.
 */
class PickAppIconFixtureTest {
    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private val appIcon get() = componentStandIn(COMPONENT_CALLS.keys.first()).substringBefore("->")

    /** Every call on PackageManager by one of [COMPONENT_CALLS]' names, whatever its form. */
    private fun Method.componentCalls() = (implementation?.instructions ?: emptyList()).filter {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        call?.definingClass == PACKAGE_MANAGER && call.name in COMPONENT_CALLS
    }

    private fun callers(bundle: File) = FixtureDex.methodsWhere(bundle, { dex ->
        dex.methodSection.any { it.definingClass == PACKAGE_MANAGER && it.name in COMPONENT_CALLS }
    }) { it.componentCalls().isNotEmpty() }

    @Test
    fun eachBuildsComponentCallsAllMoveToAppIcon() {
        val extension = ExtensionDex.classDef(appIcon)
        val standIns = COMPONENT_CALLS.keys.map(::componentStandIn).toSet()
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val all = callers(bundle)
                // Disable Audience Network's to send, which it refuses to do when none is left.
                val setup = all.filter { it.definingClass == REMOTE_APIS_SETUP }
                assertTrue("$name: $REMOTE_APIS_SETUP turns no component on", setup.isNotEmpty())
                val callers = all - setup.toSet()
                val sites = callers.flatMap { it.componentCalls() }
                assertTrue("$name: no component call", sites.isNotEmpty())
                val odd = sites.filter { it.componentCall() == null }
                assertTrue("$name: calls the patch can't move, ${odd.map { "${it.opcode} ${(it as ReferenceInstruction).reference}" }}", odd.isEmpty())

                // Facebook's component manager turns its launcher entry back on from a class that names it.
                val naming = FixtureDex.classesHolding(bundle, FACEBOOK_ALIAS).map { it.type }.toSet()
                assertTrue("$name: no class naming $FACEBOOK_ALIAS turns a component on or off", callers.any { it.definingClass in naming })

                val types = callers.map { it.definingClass }.toSet()
                val context = PatchContexts.of(FixtureDex.classes(bundle, types + REMOTE_APIS_SETUP).values + extension)
                assertEquals("$name: every call moves", sites.size, context.routeComponentCalls())
                assertEquals("$name: Audience Network's setup keeps its calls for Disable Audience Network",
                    setup.sumOf { componentStateCalls(it).size },
                    context.mutableClassDefBy(REMOTE_APIS_SETUP).methods.sumOf { componentStateCalls(it).size })
                for (type in types) {
                    for (method in context.mutableClassDefBy(type).methods) {
                        assertEquals("$name: $type->${method.name} still calls PackageManager", emptyList<Any>(), method.componentCalls())
                        val moved = (method.implementation?.instructions ?: emptyList())
                            .mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() }
                            .filter { it.startsWith(extension.type) }
                        assertTrue("$name: $type->${method.name} calls ${moved - standIns}", moved.all { it in standIns })
                    }
                }
                val own = context.mutableClassDefBy(extension.type).methods.sumOf { it.componentCalls().size }
                assertTrue("$name: AppIcon's own calls stay", own > 0)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun withoutTheStandInsOrAnyCallItRefuses() {
        val bundle = declaredBundles().values.flatten().firstOrNull() ?: return
        val callers = callers(bundle)
        val classes = FixtureDex.classes(bundle, setOf(callers.first().definingClass)).values
        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(classes).routeComponentCalls() }
        assertTrue(missing.message, missing.message.orEmpty().contains("the extension has no public static"))

        val none = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(ExtensionDex.classDef(appIcon))).routeComponentCalls() }
        assertTrue(none.message, none.message.orEmpty().contains("found no PackageManager.setComponentEnabledSetting"))
    }

    @Test
    fun eachBuildsManifestAndIconTakeTheIcons() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                withDecoded(bundle) { paths, read, manifest ->
                    val name = bundle.name
                    addIconAliases(manifest)
                    val names = manifest.documentElement.elements("activity-alias").map { it.getAttribute("android:name") }
                    val at = names.indexOf(FACEBOOK_ALIAS)
                    assertEquals("$name: after Facebook's", HUSHFACEBOOK_ICONS.map { it.alias },
                        names.subList(at + 1, at + 1 + HUSHFACEBOOK_ICONS.size))

                    val icon = checkNotNull(appResource(applicationIcon(manifest))) { "$name: icon" }
                    val iconPath = singleXml(paths, icon, ADAPTIVE_ICON, { xml(read(it)) })
                    val glyph = monochromeReference(xml(read(iconPath)))
                    val files = iconFiles(read(iconPath), xml(read(singleXml(paths, glyph, VECTOR, { xml(read(it)) }))))
                    assertEquals("$name: an icon per alias", HUSHFACEBOOK_ICONS.map { it.mipmap }, files.keys.toList())
                    for ((mipmap, text) in files) assertEquals("$name: $mipmap", ADAPTIVE_ICON, xml(text).documentElement.tagName)
                    assertEquals("$name: the Facebook one is Facebook's own", xml(read(iconPath)).documentElement.elements("foreground")
                        .single().getAttribute("android:drawable"),
                        xml(files.getValue("hushfacebook_icon_facebook")).documentElement.elements("foreground").single().getAttribute("android:drawable"))
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    /** [bundle]'s base APK manifest and launcher icon files, decoded by ARSCLib, Morphe's resource library. */
    private fun withDecoded(bundle: File, check: (List<String>, (String) -> String, Document) -> Unit) {
        val work = Files.createTempDirectory("hushfacebook-pick-icon").toFile()
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
            val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(decoded.walk().single { it.name == "AndroidManifest.xml" })
            check(xmls.keys.toList(), xmls::getValue, manifest)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun xml(text: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())

    private fun Element.elements(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }
}
