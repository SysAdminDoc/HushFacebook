/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.readsAfter
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Install beside Meta's apps against each declared Facebook build: what the manifest declares,
 * every mention the rename moves, and every place the code names either permission.
 *
 * The patch renames two names and routes three literals. That's only enough if the build declares
 * nothing else another Meta app could declare, and names the two nowhere the routing can't reach,
 * such as a static field's value or another spelling. Both are pinned here, build by build. So is
 * the same for a copy renamed with Morphe's Clone app, which has to install and start beside the
 * Facebook it was cloned from.
 */
class SharedPermissionsFixtureTest {
    private val android = "http://schemas.android.com/apk/res/android"

    /** The base APK's binary manifest. */
    private fun manifestOf(bundle: File): ByteBuffer {
        ZipFile(bundle).use { zip ->
            val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
            ZipInputStream(zip.getInputStream(base).buffered()).use { apk ->
                while (true) {
                    val entry = apk.nextEntry ?: break
                    if (entry.name == "AndroidManifest.xml") return ByteBuffer.wrap(apk.readBytes())
                }
            }
        }
        throw AssertionError("${bundle.name}: the base APK has no manifest")
    }

    /**
     * The binary manifest as the kind of document the resource patch is handed: elements by tag,
     * Android's attributes as `android:<name>`, every value as text.
     */
    private fun documentOf(xml: ByteBuffer): Document {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val parser = AndroidBinXmlParser(xml)
        val open = ArrayDeque<Node>().apply { addLast(document) }
        while (true) {
            when (parser.next()) {
                AndroidBinXmlParser.EVENT_START_ELEMENT -> {
                    val element = document.createElement(parser.name)
                    for (at in 0 until parser.attributeCount) {
                        val name = parser.getAttributeName(at)
                        check(name.isNotEmpty()) { "attribute $at of <${parser.name}> has no name" }
                        val qualified = if (parser.getAttributeNamespace(at) == android) "android:$name" else name
                        val value = when (parser.getAttributeValueType(at)) {
                            AndroidBinXmlParser.VALUE_TYPE_STRING -> parser.getAttributeStringValue(at)
                            AndroidBinXmlParser.VALUE_TYPE_BOOLEAN -> parser.getAttributeBooleanValue(at).toString()
                            else -> runCatching { parser.getAttributeIntValue(at).toString() }.getOrDefault("")
                        }
                        element.setAttribute(qualified, value)
                    }
                    open.last().appendChild(element)
                    open.addLast(element)
                }
                AndroidBinXmlParser.EVENT_END_ELEMENT -> open.removeLast()
                AndroidBinXmlParser.EVENT_END_DOCUMENT -> return document
            }
        }
    }

    private fun Document.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Document.values(): List<String> = elements("*").flatMap { element ->
        (0 until element.attributes.length).map { (element.attributes.item(it) as Attr).value }
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** Parameters compared as text: dexlib2's lists of two kinds don't equal each other. */
    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    @Test
    fun eachDeclaredBuildDeclaresNothingElseAMetaAppCouldAndRenamesEveryMention() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val document = documentOf(manifestOf(bundle))

                // Every permission Facebook declares, by name and protection level. The shared two are
                // signature-level, and everything else is named after Facebook's own package, which
                // no other app declares. A new name outside both has to be read before it ships.
                val declared = document.elements("permission")
                    .associate { it.getAttribute("android:name") to it.getAttribute("android:protectionLevel") }
                assertEquals("${bundle.name}: the shared permissions and their protection",
                    SHARED_PERMISSIONS.associateWith { "2" }, declared.filterKeys { it in SHARED_PERMISSIONS })
                assertEquals("${bundle.name}: declared permissions another app could declare too", emptyList<String>(),
                    declared.keys.filter { it !in SHARED_PERMISSIONS && !it.startsWith("com.facebook.katana.") })

                val moved = document.renameSharedPermissions()

                // FB_APP_COMMUNICATION guards 47 components on 583, one more than 582 and 580 had (27
                // activities, 14 receivers, 4 services and a provider on 580). The other guards only
                // the device id receiver.
                assertEquals("${bundle.name}: FB_APP_COMMUNICATION", Mentions(1, 1, 47), moved[APP_COMMUNICATION])
                assertEquals("${bundle.name}: receiver.permission.ACCESS", Mentions(1, 1, 1), moved[RECEIVER_ACCESS])
                val values = document.values()
                assertEquals("${bundle.name}: shared names left", emptyList<String>(), values.filter { it in SHARED_PERMISSIONS })
                assertEquals(49, values.count { it == renamed(APP_COMMUNICATION) })
                assertEquals(3, values.count { it == renamed(RECEIVER_ACCESS) })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachDeclaredBuildNamesThePermissionsOnlyWhereTheRoutingReaches() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val spellings = sortedSetOf<String>()
                val fields = mutableListOf<String>()
                val methods = mutableMapOf<String, Int>()
                val callers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    dex.stringSection.filterTo(spellings) {
                        it.contains("FB_APP_COMMUNICATION") || it.contains("receiver.permission.ACCESS")
                    }
                    if (dex.stringSection.none { it in SHARED_LITERALS }) return@forEach
                    for (classDef in dex.classes) {
                        classDef.staticFields
                            .filter { (it.initialValue as? StringEncodedValue)?.value in SHARED_LITERALS }
                            .forEach { fields += "${classDef.type}->${it.name}" }
                        var loads = false
                        for (method in classDef.methods) {
                            val literals = method.instructions().mapNotNull { it.sharedLiteral() }.toSet()
                            literals.forEach { methods.merge(it, 1, Int::plus) }
                            loads = loads || literals.isNotEmpty()
                        }
                        if (loads) callers += ImmutableClassDef.of(classDef)
                    }
                }

                // No other spelling, and nothing kept in a field: every mention is a load the patch routes.
                assertEquals("${bundle.name}: spellings of the two names", emptyList<String>(),
                    spellings.filter { it !in SHARED_LITERALS })
                assertEquals("${bundle.name}: fields holding a name", emptyList<String>(), fields)
                // The cross-app broadcast manager, Profilo's trace control twice, and the login state
                // broadcast use the format; the MQTT service's receiver and the zero-rating switch the name.
                assertEquals("${bundle.name}: methods loading each literal",
                    mapOf(APP_COMMUNICATION to 2, APP_COMMUNICATION_FORMAT to 4), methods)

                val context = PatchContexts.of(callers)
                assertEquals("${bundle.name}: loads routed", 6, context.routeSharedLiterals())
                for (before in callers) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val was = original.instructions()
                        if (was.none { it.sharedLiteral() != null }) continue
                        val where = "${bundle.name}: ${original.definingClass}->${original.name}"
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        assertEquals("$where: two instructions per load", was.size + 2 * was.count { it.sharedLiteral() != null },
                            now.size)
                        now.withIndex().filter { it.value.sharedLiteral() != null }.forEach { (index, load) ->
                            val register = (load as OneRegisterInstruction).registerA
                            val call = now[index + 1]
                            assertEquals("$where at $index", NAME_CALL, (call as ReferenceInstruction).reference.toString())
                            assertEquals("$where at $index: the call's register", register to 1,
                                (call as RegisterRangeInstruction).startRegister to call.registerCount)
                            assertEquals("$where at $index: the answer's register", register,
                                (now[index + 2] as OneRegisterInstruction).registerA)
                        }
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Each declared build as Morphe's Clone app leaves it after the default patches, with each of
     * its two options on and off, then followed by this bundle: nothing names a permission only the
     * stock app declares, no authority is one the stock app claims, and nothing it declares is one
     * Meta's own Facebook declares, so it installs beside that too (#60). Two things the runtime half
     * takes for granted are pinned too: every authority is under Facebook's package, which is what
     * the extension moves, and every process is named after the package, which is how it learns
     * the clone's name before Facebook has a context.
     */
    @Test
    fun eachDeclaredBuildClonedWithEitherOptionIsSelfConsistent() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val clone = "$FACEBOOK.morphe"
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val xml = manifestOf(bundle)
                val stock = documentOf(xml.duplicate()).apply { renameSharedPermissions() }
                val stockDeclared = stock.elements("permission").map { it.getAttribute("android:name") }.toSet()
                val stockAuthorities = stock.ownAuthorities(FACEBOOK)
                val stockPermissions = stock.ownPermissions(FACEBOOK)
                val metaDeclared = documentOf(xml.duplicate()).elements("permission").map { it.getAttribute("android:name") }.toSet()
                assertEquals("${bundle.name}: Facebook's own permissions", 5, stockPermissions.size)
                assertEquals("${bundle.name}: authorities outside $FACEBOOK", emptyList<String>(),
                    stock.authorities().filterNot { it.trim() in stockAuthorities })
                assertTrue("${bundle.name}: the dedup provider", "$FACEBOOK.ClientMessagePushDedupInfoProvider" in stockAuthorities)
                assertEquals("${bundle.name}: processes not named after the package", emptyList<String>(),
                    stock.elements("*").map { it.getAttribute("android:process") }.filter { it.isNotEmpty() && !it.startsWith(":") })

                for (permissions in listOf(true, false)) {
                    for (providers in listOf(true, false)) {
                        val where = "${bundle.name}, Update permissions $permissions, Update providers $providers"
                        val document = documentOf(xml.duplicate())
                        document.renameSharedPermissions()
                        document.cloneApp(clone, permissions, providers)

                        document.followRenamedPackage(FACEBOOK, stockAuthorities, stockPermissions)

                        val declared = document.elements("permission").map { it.getAttribute("android:name") }.toSet()
                        assertEquals("$where: declarations Meta's Facebook owns", emptySet<String>(), declared.intersect(metaDeclared))
                        assertEquals("$where: declarations", stockDeclared.size, declared.size)
                        val strays = document.elements("*").flatMap { element ->
                            (0 until element.attributes.length).map { element.attributes.item(it) as Attr }
                                .filterNot { element.tagName == "permission" && it.name == "android:name" }
                                .filter { it.value in stockDeclared && it.value !in declared }
                                .map { "<${element.tagName}> ${element.getAttribute("android:name")} ${it.name}=${it.value}" }
                        }
                        assertEquals("$where: mentions of a permission only the stock app declares", emptyList<String>(), strays)
                        assertEquals("$where: authorities the stock app claims", emptyList<String>(),
                            document.authorities().filter { it.trim() in stockAuthorities })
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Facebook's code spells some of its own authorities out, and a clone's code would reach the
     * stock app's providers through them. Every one is a load the routing reaches: a method loads
     * it with const-string, no field keeps one, and no other string names one.
     */
    @Test
    fun eachDeclaredBuildsOwnAuthoritiesAreLoadsTheRoutingReaches() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val authorities = documentOf(manifestOf(bundle)).ownAuthorities(FACEBOOK)
                fun Instruction.authority() = ((this as? ReferenceInstruction)?.reference as? StringReference)
                    ?.string?.ownAuthority(authorities)
                val literals = sortedSetOf<String>()
                val elsewhere = sortedSetOf<String>()
                val fields = mutableListOf<String>()
                val callers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    val strings = dex.stringSection.filter { text -> authorities.any { text.contains(it) } }
                    strings.filterTo(literals) { it.ownAuthority(authorities) != null }
                    strings.filterTo(elsewhere) { it.ownAuthority(authorities) == null }
                    if (strings.none { it.ownAuthority(authorities) != null }) return@forEach
                    for (classDef in dex.classes) {
                        classDef.staticFields
                            .filter { (it.initialValue as? StringEncodedValue)?.value?.ownAuthority(authorities) != null }
                            .forEach { fields += "${classDef.type}->${it.name}" }
                        if (classDef.methods.any { method -> method.instructions().any { it.authority() != null } }) {
                            callers += ImmutableClassDef.of(classDef)
                        }
                    }
                }

                assertTrue("${bundle.name}: the stock dedup address a clone deleted through",
                    "content://$FACEBOOK.ClientMessagePushDedupInfoProvider/mutestatus" in literals)
                assertEquals("${bundle.name}: fields holding an authority", emptyList<String>(), fields)
                assertEquals("${bundle.name}: other strings naming an authority", emptySet<String>(), elsewhere)
                val context = PatchContexts.of(callers)
                assertEquals("${bundle.name}: loads routed", AUTHORITY_LOADS.getValue(version),
                    context.routeOwnAuthorities(FACEBOOK, authorities))
                for (before in callers) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val was = original.instructions()
                        val loads = was.count { it.authority() != null }
                        if (loads == 0) continue
                        val where = "${bundle.name}: ${original.definingClass}->${original.name}"
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        assertEquals("$where: two instructions per load", was.size + 2 * loads, now.size)
                        now.withIndex().filter { it.value.authority() != null }.forEach { (index, load) ->
                            val register = (load as OneRegisterInstruction).registerA
                            val call = now[index + 1]
                            assertEquals("$where at $index", AUTHORITY_CALL, (call as ReferenceInstruction).reference.toString())
                            assertEquals("$where at $index: the call's register", register to 1,
                                (call as RegisterRangeInstruction).startRegister to call.registerCount)
                            assertEquals("$where at $index: the answer's register", register,
                                (now[index + 2] as OneRegisterInstruction).registerA)
                        }
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The rest of Facebook's own authorities are put together from BuildConstants' name and a
     * suffix, and the cache one closed a clone (#16). The routing finds exactly the suffixes in
     * [BUILT_AUTHORITIES] put after that name, leaves the ones in [OTHER_PREFIX_AUTHORITIES] (put
     * after something else), and each built name then goes straight through the extension.
     */
    @Test
    fun eachDeclaredBuildsBuiltAuthoritiesAreTheOnesTheRoutingReaches() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val authorities = documentOf(manifestOf(bundle)).ownAuthorities(FACEBOOK)
                val routed = sortedSetOf<String>()
                val left = sortedSetOf<String>()
                val callers = mutableListOf<ClassDef>()
                var results = 0
                FixtureDex.forEach(bundle) { dex ->
                    // By the literals the routing takes, so a dex with only a content:// address and a path is read too.
                    if (dex.stringSection.none { it.buildsOwnAuthority(FACEBOOK, authorities) }) return@forEach
                    for (classDef in dex.classes) {
                        var builds = false
                        for (method in classDef.methods) {
                            val code = method.instructions()
                            val loads = code.indices.filter { code[it].loadedString()?.buildsOwnAuthority(FACEBOOK, authorities) == true }
                            if (loads.isEmpty()) continue
                            val built = method.builtAuthorityResults(FACEBOOK, authorities)
                            results += built.size
                            builds = builds || built.isNotEmpty()
                            for (load in loads) {
                                val readers = method.readsAfter(load, (code[load] as OneRegisterInstruction).registerA)
                                val into = if (built.any { it - 1 in readers }) routed else left
                                into += code[load].loadedString()!!
                            }
                        }
                        if (builds) callers += ImmutableClassDef.of(classDef)
                    }
                }

                assertTrue("${bundle.name}: the cache authority that closed a clone", ".provider.CacheProvider" in routed)
                assertEquals("${bundle.name}: suffixes put after BuildConstants' name", BUILT_AUTHORITIES.getValue(version), routed)
                assertEquals("${bundle.name}: suffixes put after anything else", OTHER_PREFIX_AUTHORITIES.getValue(version), left)
                val context = PatchContexts.of(callers)
                assertEquals("${bundle.name}: built names routed", results, context.routeBuiltAuthorities(FACEBOOK, authorities))
                for (before in callers) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val built = original.builtAuthorityResults(FACEBOOK, authorities)
                        if (built.isEmpty()) continue
                        val where = "${bundle.name}: ${original.definingClass}->${original.name}"
                        val was = original.instructions()
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        assertEquals("$where: two instructions per built name", was.size + 2 * built.size, now.size)
                        built.forEachIndexed { done, at ->
                            val index = at + 2 * done
                            val register = (now[index] as OneRegisterInstruction).registerA
                            val call = now[index + 1]
                            assertEquals("$where at $index", AUTHORITY_CALL, (call as ReferenceInstruction).reference.toString())
                            assertEquals("$where at $index: the call's register", register to 1,
                                (call as RegisterRangeInstruction).startRegister to call.registerCount)
                            assertEquals("$where at $index: the answer's register", register,
                                (now[index + 2] as OneRegisterInstruction).registerA)
                            assertEquals("$where at $index: nothing put after the answer", was[at + 1].opcode, now[index + 3].opcode)
                        }
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The rows under Supported links for Meta App Manager (issue #30), Messenger and Instagram (#78)
     * read each app's package without QUERY_ALL_PACKAGES. Android 11 and later only answer for a
     * package `<queries>` covers, so a build that drops it would hide the row on every phone,
     * silently. App Manager is named. Messenger and Instagram aren't: Facebook sees them through its
     * bare MAIN intent query, which any app with a launcher activity matches (on a phone, `dumpsys
     * package queries` lists them under "queries via component").
     */
    @Test
    fun eachDeclaredBuildQueriesTheLinkHolders() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val document = documentOf(manifestOf(bundle))
                val queried = document.elements("queries").flatMap { queries ->
                    val packages = queries.getElementsByTagName("package")
                    (0 until packages.length).map { (packages.item(it) as Element).getAttribute("android:name") }
                }
                assertTrue("${bundle.name}: <queries> doesn't name $APP_MANAGER", APP_MANAGER in queried)
                val launchable = document.elements("queries").flatMap { queries ->
                    val intents = queries.getElementsByTagName("intent")
                    (0 until intents.length).map { intents.item(it) as Element }
                }.any { intent ->
                    val actions = intent.getElementsByTagName("action")
                    actions.length == 1 &&
                        (actions.item(0) as Element).getAttribute("android:name") == "android.intent.action.MAIN" &&
                        intent.getElementsByTagName("category").length == 0 &&
                        intent.getElementsByTagName("data").length == 0
                }
                assertTrue("${bundle.name}: no bare MAIN intent query, so Messenger and Instagram are hidden", launchable)
                assertTrue("${bundle.name}: asks for every package, so <queries> isn't what makes the holders visible",
                    document.elements("uses-permission").none {
                        it.getAttribute("android:name") == "android.permission.QUERY_ALL_PACKAGES"
                    })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Each declared build's notification templates that build RemoteViews from Facebook's package
     * spelled out hand it to the extension first (#36), and afterwards no RemoteViews in those
     * classes is built straight from the literal: the nearest load of its package register is the
     * extension's answer or something other than Facebook's name.
     */
    @Test
    fun eachDeclaredBuildsNotificationViewsTakeTheRunningPackage() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, FACEBOOK)
                val context = PatchContexts.of(holders)
                assertEquals("${bundle.name}: RemoteViews package loads routed", REMOTE_VIEWS_LOADS.getValue(version),
                    context.routeRemoteViewsPackage(FACEBOOK))

                var built = 0
                for (before in holders) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        val where = "${bundle.name}: ${original.definingClass}->${original.name}"
                        now.withIndex().filter { (_, it) ->
                            it.opcode == Opcode.INVOKE_DIRECT &&
                                (it as ReferenceInstruction).reference.toString() == REMOTE_VIEWS_INIT
                        }.forEach { (index, init) ->
                            val register = (init as FiveRegisterInstruction).registerD
                            val loadAt = (index - 1 downTo 0).firstOrNull { at ->
                                val it = now[at]
                                (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO ||
                                    it.opcode == Opcode.MOVE_RESULT_OBJECT) && (it as OneRegisterInstruction).registerA == register
                            } ?: return@forEach
                            val load = now[loadAt]
                            val literal = ((load as? ReferenceInstruction)?.reference as? StringReference)?.string
                            assertTrue("$where at $index: RemoteViews still built from $FACEBOOK", literal != FACEBOOK)
                            if (load.opcode == Opcode.MOVE_RESULT_OBJECT &&
                                (now.getOrNull(loadAt - 1) as? ReferenceInstruction)?.reference?.toString() == PACKAGE_CALL) built++
                        }
                    }
                }
                assertTrue("${bundle.name}: no RemoteViews takes the routed package", built >= REMOTE_VIEWS_LOADS.getValue(version))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * #36 leftovers: each declared build's places that name Facebook to Android as this app or look
     * up its resources hand the name to the extension first (Firebase's notification code, a chat
     * notification's intent, app info, ProxyAuth and the launcher badge), and afterwards only the
     * setPackage calls that mean Meta's Facebook still take its name straight from a literal.
     */
    @Test
    fun eachDeclaredBuildsOwnIntentsTakeTheRunningPackage() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, FACEBOOK)
                val context = PatchContexts.of(holders)
                assertEquals("${bundle.name}: own package calls routed", OWN_PACKAGE_CALL_LOADS.getValue(version),
                    context.routeOwnPackageCalls(FACEBOOK))

                var inFirebase = 0
                val stillFacebook = mutableListOf<String>()
                for (before in holders) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        val where = "${original.definingClass}->${original.name}"
                        if (original.definingClass == FIREBASE_MESSAGING) {
                            inFirebase += now.count { (it as? ReferenceInstruction)?.reference?.toString() == PACKAGE_CALL }
                        }
                        now.withIndex().filter { (_, it) ->
                            it.opcode == Opcode.INVOKE_VIRTUAL && (it as ReferenceInstruction).reference.toString() == SET_PACKAGE
                        }.forEach { (index, call) ->
                            val register = (call as FiveRegisterInstruction).registerD
                            val load = (index - 1 downTo 0).map { now[it] }.firstOrNull {
                                (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO ||
                                    it.opcode == Opcode.MOVE_RESULT_OBJECT) && (it as OneRegisterInstruction).registerA == register
                            } ?: return@forEach
                            val literal = ((load as? ReferenceInstruction)?.reference as? StringReference)?.string
                            if (literal == FACEBOOK) stillFacebook += "$where at $index"
                        }
                    }
                }
                assertEquals("${bundle.name}: Firebase's notification calls routed", FIREBASE_LOADS.getValue(version), inFirebase)
                assertTrue("${bundle.name}: Firebase still sets $FACEBOOK: $stillFacebook",
                    stillFacebook.none { it.startsWith(FIREBASE_MESSAGING) })
                assertEquals("${bundle.name}: setPackage left with $FACEBOOK: $stillFacebook",
                    SETS_REAL_FACEBOOK.getValue(version).forBundle(bundle.name), stillFacebook.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * #113: each declared build's lookup of the running package among Meta's apps (the encrypted
     * chat backup's preferences picker, which throws when nothing matches) hands the package it
     * reads to the extension, once, straight after getPackageName's result is moved, and that
     * method is the one choosing between Messenger's and Facebook's backup preferences.
     */
    @Test
    fun eachDeclaredBuildsMetaAppLookupReadsAsFacebook() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, MESSENGER_DEBUG)
                val context = PatchContexts.of(holders)
                assertEquals("${bundle.name}: Meta app lookups routed", 1, context.routeMetaAppLookups())

                val routed = holders.flatMap { context.mutableClassDefBy(it.type).methods }
                    .map { it.instructions() }
                    .filter { code -> code.any { (it as? ReferenceInstruction)?.reference?.toString() == AS_FACEBOOK_CALL } }
                assertEquals("${bundle.name}: methods calling the extension", 1, routed.size)
                val code = routed.single()
                val at = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == AS_FACEBOOK_CALL }
                assertEquals(GET_PACKAGE_NAME, (code[at - 2] as ReferenceInstruction).reference.toString())
                val register = (code[at - 1] as OneRegisterInstruction).registerA
                assertEquals(register, (code[at] as RegisterRangeInstruction).startRegister)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
                assertEquals(register, (code[at + 1] as OneRegisterInstruction).registerA)
                val strings = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                assertTrue("${bundle.name}: the routed lookup picks the backup preferences: $strings",
                    "fbautobackupprefs" in strings && "autobackupprefs" in strings)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Each declared build's in-app browser binds its renderers in the running app: the one
     * getPackageName() answering Facebook's package for this app goes through the extension, and it
     * belongs to the connection that names the browser's SandboxedProcessService.
     */
    @Test
    fun eachDeclaredBuildsBrowserRenderersBindInTheRunningApp() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, FACEBOOK)
                val context = PatchContexts.of(holders)
                assertEquals("${bundle.name}: getPackageName answers routed", OWN_PACKAGE_NAME_ANSWERS.getValue(version),
                    context.routeOwnPackageNameAnswers(FACEBOOK))

                val routed = holders.map { context.mutableClassDefBy(it.type) }.filter { owner ->
                    owner.methods.any { method ->
                        method.name == "getPackageName" &&
                            method.instructions().any { (it as? ReferenceInstruction)?.reference?.toString() == PACKAGE_CALL }
                    }
                }
                assertEquals("${bundle.name}: classes answering through the extension", 1, routed.size)
                val strings = routed.single().methods.flatMap { it.instructions() }.mapNotNull { it.loadedString() }
                assertTrue("${bundle.name}: the routed answer is the browser's renderer connection: $strings",
                    SANDBOXED_PROCESS_SERVICE in strings)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The call [instruction] makes if it's one that can mean this app when handed Facebook's
     * package ([OWNER_ARGUMENTS]) and [register] goes in that argument, else null.
     */
    private fun ownerCall(instruction: Instruction, register: Int): String? {
        val called = ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString() ?: return null
        val position = OWNER_ARGUMENTS[called] ?: return null
        val arguments = (instruction as? FiveRegisterInstruction)
            ?.let { listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount) }
            ?: (instruction as? RegisterRangeInstruction)
                ?.let { (it.startRegister until it.startRegister + it.registerCount).toList() } ?: return null
        return if (arguments.getOrNull(position) == register) called.substringAfter("->").substringBefore('(') else null
    }

    /**
     * The [ownerCall]s that read what [this] put in [register] at [at], straight or after a move
     * into another register.
     */
    private fun Method.ownerReads(at: Int, register: Int, code: List<Instruction>, moves: Int = 0): Set<String> =
        readsAfter(at, register).flatMapTo(sortedSetOf()) { reader ->
            val instruction = code[reader]
            if (instruction.opcode in MOVES && moves < 4) {
                ownerReads(reader, (instruction as TwoRegisterInstruction).registerA, code, moves + 1)
            } else {
                listOfNotNull(ownerCall(instruction, register))
            }
        }

    /**
     * #16 inventory: in each declared build, every load of Facebook's bare package that a call able
     * to mean this app reads, straight or after a move, is either one the patch hands to the
     * extension or one of the places where it means Meta's Facebook ([STOCK_OWNERS]). A new
     * place of either kind fails here until someone sorts it.
     */
    @Test
    fun eachDeclaredBuildsPackageLoadsAreSortedIntoThisAppAndMetasFacebook() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                var routed = 0
                val stock = mutableListOf<String>()
                FixtureDex.forEach(bundle) { dex ->
                    if (FACEBOOK !in dex.stringSection) return@forEach
                    for (classDef in dex.classes) {
                        for (method in classDef.methods) {
                            val code = method.instructions()
                            val loads = code.indices.filter { code[it].loadedString() == FACEBOOK }
                            if (loads.isEmpty()) continue
                            val handed = (method.ownPackageCallLoads(FACEBOOK) + method.remoteViewsPackageLoads(FACEBOOK)).toSet()
                            for (at in loads) {
                                if (at in handed) {
                                    routed++
                                    continue
                                }
                                val reads = method.ownerReads(at, (code[at] as OneRegisterInstruction).registerA, code)
                                if (reads.isNotEmpty()) stock += "${classDef.type}->${method.name}@$at: ${reads.joinToString()}"
                            }
                        }
                    }
                }
                assertEquals("${bundle.name}: package loads handed to the extension",
                    OWN_PACKAGE_CALL_LOADS.getValue(version) + REMOTE_VIEWS_LOADS.getValue(version), routed)
                val named = STOCK_OWNERS.getValue(version).forBundle(bundle.name)
                assertEquals("${bundle.name}: $FACEBOOK left where it could mean this app: $stock", named.size, stock.size)
                for (site in named) {
                    assertEquals("${bundle.name}: $site among $stock", 1, stock.count { site in it })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Document.authorities(): List<String> =
        elements("provider").flatMap { it.getAttribute("android:authorities").split(';') }

    /** The entry for the fixture bundle [name], keyed by what follows the version in it. */
    private fun <T> Map<String, T>.forBundle(name: String): T = entries.single { "-${it.key}." in name }.value

    private companion object {
        const val FACEBOOK = AppCompatibilities.FACEBOOK_PACKAGE

        /** SupportedLinks.APP_MANAGER in the extension. */
        const val APP_MANAGER = "com.facebook.appmanager"

        /** Loads of an own authority per build, counted off the fixtures with a separate dex scan. */
        val AUTHORITY_LOADS = mapOf(AppCompatibilities.FACEBOOK_TARGET_VERSION to 13)

        /**
         * Own authority suffixes Facebook puts after BuildConstants' name, per build, from a dex probe
         * of arm64 584 (2026-10-23): the cache, the user values (lite and full, first-party and
         * not), contacts connections, the two deferred deep link providers' own matchers, and
         * the attribution id pattern.
         */
        val BUILT_AUTHORITIES = mapOf(
            AppCompatibilities.FACEBOOK_TARGET_VERSION to sortedSetOf(
                ".liteprovider.FirstPartyUserValuesLiteProvider", ".provider.CacheProvider",
                ".provider.ContactsConnectionsProvider", ".provider.DeferredDeeplinkProvider",
                ".provider.FirstPartyUserValuesProvider", ".provider.ThreadsTixuDeferredDeeplinkProvider",
                ".provider.UserValuesProvider", "content://%s.provider.AttributionIdProvider",
            ),
        )

        /**
         * Own authority suffixes put after something else, per build (arm64 584, 2026-10-23). No
         * method loading one calls BuildConstants.A04(), the only reader of its Facebook name: the
         * phone id request to each Meta app's package and the access library request to the package
         * it's handed, which mean that app, the platform and photos providers, whose package comes
         * from Facebook's injector, and the short suffixes (the secure file provider, threads
         * properties, contacts, messages and the rest), each in a method that never asks
         * BuildConstants.
         */
        val OTHER_PREFIX_AUTHORITIES = mapOf(
            AppCompatibilities.FACEBOOK_TARGET_VERSION to sortedSetOf(
                ".contacts", ".experiments", ".hatch.chat.files", ".hatch.files", ".identity.feo2.api", ".messages",
                ".provider.AccessLibraryContentProvider", ".provider.PhotosProvider", ".provider.PlatformProvider",
                ".provider.phoneid", ".securefileprovider", ".tam-attachment", ".threads_properties",
            ),
        )

        /**
         * Loads of Facebook's package that only build RemoteViews, per build. 582 had 14 notification
         * template sites (2026-10-14). 583 builds all but one from the running package itself, and
         * the check after the patch still finds none left spelled out (2026-10-16). 584 spelled all
         * 14 out again (2026-10-22). 585 is back to 583's one (2026-10-29).
         */
        val REMOTE_VIEWS_LOADS = mapOf(AppCompatibilities.FACEBOOK_TARGET_VERSION to 1)

        /**
         * Loads of Facebook's package that only name this app to Android or look up its resources,
         * per build. From a dump of the arm64 582 dex (2026-10-14), the same on 583:
         * FirebaseMessagingService's two setPackage, one launch intent and three meta-data reads, and
         * one chat notification's setPackage. Added from the 583 inventory of both ABIs
         * (2026-10-20): FirebaseMessagingService's three resource lookups (the notification text,
         * icon and sound URI), Firebase's own ApplicationInfo and its resource lookup, the manifest
         * meta-data read for the build revision, AppInfoCore's ApplicationInfo and icon, ProxyAuth's
         * three setClassName (login and consent deep links, device login) and Xiaomi's launcher
         * badge call.
         */
        val OWN_PACKAGE_CALL_LOADS = mapOf(AppCompatibilities.FACEBOOK_TARGET_VERSION to 19)

        /** Of those, the ones in Firebase's notification code. */
        val FIREBASE_LOADS = mapOf(AppCompatibilities.FACEBOOK_TARGET_VERSION to 9)

        /**
         * getPackageName() overrides answering Facebook's package for the running app, per build: the
         * in-app browser's renderer connection (arm64 585 LX/0BpS, 2026-11-02). Meta's app table, the
         * other override loading it, answers from a switch and keeps it.
         */
        val OWN_PACKAGE_NAME_ANSWERS = mapOf(AppCompatibilities.FACEBOOK_TARGET_VERSION to 1)

        /** The in-app browser's renderer service, named next to the package it runs in. */
        const val SANDBOXED_PROCESS_SERVICE = "com.facebook.browser.helium.content.SandboxedProcessService"

        /**
         * Calls that can mean this app when handed Facebook's package, with the argument it goes in
         * (0 is the receiver of a virtual call), for the inventory.
         */
        val OWNER_ARGUMENTS = mapOf(
            SET_PACKAGE to 1,
            SET_CLASS_NAME to 1,
            LAUNCH_INTENT to 1,
            APPLICATION_INFO to 1,
            APPLICATION_ICON to 1,
            PROVIDER_CALL to 3,
            RESOURCE_ID to 3,
            REDEX_RESOURCE_ID to 3,
            REMOTE_VIEWS_INIT to 1,
            "Landroid/content/ComponentName;-><init>(Ljava/lang/String;Ljava/lang/String;)V" to 1,
        )

        val MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

        /**
         * Loads of Facebook's package, per build and fixture bundle, that such a call reads but
         * that keep the literal, because there it means Meta's Facebook app (583, both ABIs,
         * 2026-10-20), each found once, and nothing else left: the pick of whichever Facebook build
         * is installed to open a link; Horizon's launch of Facebook after its install check; the
         * Meta app picker that hands a link to the installed Facebook unless that's the running
         * app; the forum share link, which checks Facebook is installed and opens its activity; and
         * Messenger's warm-start hint (ThreadSettingsActivity), which checks the same and then
         * sends Facebook a broadcast. Each also reads the load for something else, an install check
         * at least, so it can't go over whole. Earlier builds named these classes differently per
         * ABI, as 585's do; 584's two happened to agree. 585's phone builds drop the warm-start
         * hint, which no longer reads a load of Facebook's package there, and the Android 9 build
         * still has it (2026-11-07).
         */
        val STOCK_OWNERS = mapOf(
            AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
                "arm64-v8a" to listOf("LX/0gpz;->A00@584:", "LX/0Tav;->A00@42:", "LX/0aLJ;->A00@26:", "LX/0ZoC;->openForumShareLink\$${FORUM_LINK}@60:"),
                "armeabi-v7a" to listOf("LX/0gpb;->A00@584:", "LX/0TaH;->A00@42:", "LX/0aKz;->A00@26:", "LX/0Znr;->openForumShareLink\$${FORUM_LINK}@60:"),
                "arm64-v8a-minapi28-storeless" to listOf(
                    "LX/0UM8;->A00@579:", "LX/0NTh;->A00@42:", "LX/0lB8;->A00@24:", "LX/0NWL;->openForumShareLink\$${FORUM_LINK}@60:",
                    "Lcom/facebook/messaging/threadsettings2/activity/ThreadSettingsActivity;->onActivityCreate@50:",
                ),
            ),
        )

        const val FORUM_LINK = "fbandroid_java_com_facebook_messaging_linkhandling_linkhandling"

        /**
         * How many setPackage calls still take Facebook's package from a literal, per build and
         * fixture bundle, because there it means Meta's Facebook app: Messenger's warm-start hint
         * (ThreadSettingsActivity), sent to Facebook's WarmStartBroadcastReceiver once it has
         * checked Facebook is installed. 582 also had Messenger's deep link launcher, which 583 no
         * longer spells out. 585's phone builds no longer spell out the warm-start hint either, so
         * none is left there, and the Android 9 build keeps it (2026-11-07).
         */
        val SETS_REAL_FACEBOOK = mapOf(
            AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf("arm64-v8a" to 0, "armeabi-v7a" to 0, "arm64-v8a-minapi28-storeless" to 1),
        )
    }
}
