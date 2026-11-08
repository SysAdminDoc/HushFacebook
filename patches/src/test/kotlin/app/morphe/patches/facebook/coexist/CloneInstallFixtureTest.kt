/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.downloads.photo.PHOTO_SEARCH_AUTHORITY_SUFFIX
import app.morphe.patches.facebook.downloads.photo.declarePhotoSearchProvider
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Clone install under another package name (#417) on every Facebook build the bundle declares:
 * the decoded manifest, after the default patches' shared permissions and Search photo's
 * provider, moves under the clone with nothing left that Facebook installed beside it would also
 * claim, and the code has the one internal activity check, which goes through the extension.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class CloneInstallFixtureTest {
    private val facebook = AppCompatibilities.FACEBOOK_PACKAGE
    private val clone = CLONE_DEFAULT_PACKAGE

    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The base APK's manifest decoded by ARSCLib, the decoder Morphe's resource coder extends. */
    private fun decodedManifest(bundle: File): Document {
        val work = Files.createTempDirectory("hushfacebook-clone").toFile()
        try {
            val apk = File(work, "base.apk")
            ZipFile(bundle).use { zip ->
                val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
                zip.getInputStream(base).use { input -> apk.outputStream().use { input.copyTo(it) } }
            }
            val decoded = File(work, "decoded")
            val module = ApkModule.loadApkFile(apk)
            try {
                ApkModuleXmlDecoder(module).decodeAndroidManifest(decoded)
            } finally {
                module.close()
            }
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(decoded.walk().single { it.name == "AndroidManifest.xml" })
        } finally {
            work.deleteRecursively()
        }
    }

    private fun Document.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun Document.attributes(): List<Pair<Element, Attr>> = all("*").flatMap { element ->
        (0 until element.attributes.length).map { element to element.attributes.item(it) as Attr }
    }

    private fun Document.affinities() = all("*").filter { it.hasAttribute("android:taskAffinity") }.map { it.getAttribute("android:taskAffinity") }

    private fun Document.text(): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(this), StreamResult(it))
    }.toString()

    @Test
    fun `each declared build's manifest moves whole under the clone`() = bundles { bundle ->
        val name = bundle.name
        val manifest = decodedManifest(bundle)
        manifest.renameSharedPermissions()
        manifest.declarePhotoSearchProvider(facebook)
        val stockAuthorities = manifest.ownAuthorities(facebook)
        val stockPermissions = manifest.ownPermissions(facebook)
        val ownAffinities = manifest.affinities().filter { it.isEmpty() || it.startsWith(":") }

        val renamed = manifest.renameForClone(clone, CLONE_DEFAULT_LABEL)

        assertEquals(name, clone, manifest.documentElement.getAttribute("package"))
        assertEquals("$name: permissions moved", stockPermissions, renamed.permissions.keys)
        assertTrue("$name: Search photo's provider moved", "$facebook$PHOTO_SEARCH_AUTHORITY_SUFFIX" in renamed.authorities)
        assertTrue("$name: no push category moved", renamed.pushCategories >= 1)
        val applicationProviders = manifest.all("provider").filter { it.parentNode.nodeName == "application" }
        val left = applicationProviders.flatMap { it.getAttribute("android:authorities").split(';') }.map { it.trim() }
            .filterNot { it.startsWith("$clone.") }
        assertEquals("$name: authorities Facebook installed beside it would claim too", emptyList<String>(), left)
        val stray = manifest.attributes().filter { (_, attribute) -> attribute.value == facebook || attribute.value in stockPermissions }
            .map { (element, attribute) -> "<${element.tagName} ${attribute.name}=${attribute.value}>" }
        assertEquals("$name: still names Facebook's package or permissions", emptyList<String>(), stray)
        assertTrue("$name: a declaration under Facebook's name",
            manifest.all("permission").map { it.getAttribute("android:name") }
                .none { it.startsWith("$facebook.") && !it.startsWith("$clone.") })
        assertEquals("$name: Facebook's ':' and empty affinities", ownAffinities, manifest.affinities().filter { it.isEmpty() || it.startsWith(":") })
        assertTrue("$name: an affinity outside the clone", manifest.affinities().all { it.isEmpty() || it.startsWith(":") || it.startsWith("$clone.") })

        val before = manifest.text()
        assertEquals("$name: the shared clone support moved something again", Followed(0, 0, 0),
            manifest.followRenamedPackage(facebook, stockAuthorities + manifest.ownAuthorities(facebook), stockPermissions))
        assertEquals(name, before, manifest.text())
    }

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    @Test
    fun `each declared build hands its internal activity check the running package`() = bundles { bundle ->
        val name = bundle.name
        val holders = FixtureDex.classesHolding(bundle, INTERNAL_ACTIVITY_MARKER).filterNot { it.type.startsWith(EXTENSION_ROOT) }
        val sites = holders.flatMap { holder -> holder.methods.flatMap { method -> method.ownComponentChecks().map { method to it } } }
        assertEquals("$name: internal activity checks", 1, sites.size)
        val (method, site) = sites.single()
        val (at, register) = site
        val original = method.code()
        assertEquals("$name: the package", "Landroid/content/ComponentName;->getPackageName()Ljava/lang/String;", original[at - 2].reference())
        assertEquals("$name: the package", Opcode.MOVE_RESULT_OBJECT, original[at - 1].opcode)
        assertEquals("$name: the comparison", "Ljava/lang/String;->equals(Ljava/lang/Object;)Z", original[at].reference())

        val context = PatchContexts.of(holders)
        assertEquals(name, 1, context.routeOwnComponentChecks())

        val routed = context.mutableClassDefBy(method.definingClass).methods.single { it.signature() == method.signature() }.code()
        assertEquals("$name: the call", Opcode.INVOKE_STATIC_RANGE, routed[at].opcode)
        assertEquals("$name: the call", AS_FACEBOOK_CALL, routed[at].reference())
        assertEquals("$name: the call's register", register to 1, (routed[at] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals("$name: its answer", Opcode.MOVE_RESULT_OBJECT, routed[at + 1].opcode)
        assertEquals("$name: into the same register", register, (routed[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: Facebook's code stays", original.map { it.opcode },
            routed.take(at).map { it.opcode } + routed.drop(at + 2).map { it.opcode })
        assertEquals("$name: a second run", 0, context.routeOwnComponentChecks())
    }
}
