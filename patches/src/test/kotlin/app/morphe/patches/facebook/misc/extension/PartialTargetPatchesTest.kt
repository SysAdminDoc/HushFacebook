/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.extension

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.ads.audiencenetwork.AUDIENCE_NETWORK_COMPONENTS
import app.morphe.patches.facebook.ads.audiencenetwork.disableAudienceNetwork
import app.morphe.patches.facebook.ads.prefetch.AD_PREFETCH_SCHEDULERS
import app.morphe.patches.facebook.ads.prefetch.blockAdPrefetchPatch
import app.morphe.patches.facebook.ads.telemetry.AD_TELEMETRY
import app.morphe.patches.facebook.ads.telemetry.blockAdTelemetryPatch
import app.morphe.patches.facebook.feed.suggested.DROPPED_SUGGESTED_FEED_UNITS
import app.morphe.patches.facebook.feed.suggested.SUGGESTED_FEED_UNITS
import app.morphe.patches.facebook.feed.suggested.requireSuggestedUnits
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The four patches that work down a list of separate targets, each run on a made-up build that
 * lacks some of its list: the patch applies to what's there and the patch log names each target it
 * went without. A build with none of the list still stops the patch.
 *
 * Hide suggested and promoted posts is run up to its unit check only: the rest of its execute block
 * reads GraphQLStory's recommendation accessor and Facebook's own filter, which
 * RecommendationAccessorFixtureTest holds on the real builds.
 */
class PartialTargetPatchesTest {
    private val settingsStatus = ExtensionDex.classDef(SETTINGS_STATUS)
    private val enqueue = ImmutableMethodReference("Lfixture/Work;", "enqueue", emptyList<String>(), "V")

    /** A scheduler whose void method hands work to WorkManager, and a constructor the patch leaves alone. */
    private fun scheduler(type: String, withVoidMethod: Boolean = true): ClassDef {
        fun method(name: String, vararg body: com.android.tools.smali.dexlib2.iface.instruction.Instruction) = ImmutableMethod(
            type, name, emptyList(), "V",
            AccessFlags.PUBLIC.value or (if (name == "<init>") AccessFlags.CONSTRUCTOR.value else 0),
            null, null, ImmutableMethodImplementation(1, body.toList(), null, null),
        )
        val work = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, enqueue)
        val methods = listOfNotNull(
            method("<init>", work, ImmutableInstruction10x(Opcode.RETURN_VOID)),
            if (withVoidMethod) method("schedule", work, ImmutableInstruction10x(Opcode.RETURN_VOID)) else null,
        )
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
    }

    private fun BytecodePatchContext.firstOpcode(type: String, method: String) =
        mutableClassDefBy(type).methods.single { it.name == method }.implementation!!.instructions.first().opcode

    /** Whether SettingsStatus.[name]() now answers true, which is what a patch that applied leaves. */
    private fun BytecodePatchContext.statusOn(name: String): Boolean {
        val body = mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.implementation!!.instructions.toList()
        return (body[0] as? NarrowLiteralInstruction)?.narrowLiteral == 1 && body[1].opcode == Opcode.RETURN
    }

    private fun missingLine(patch: String, name: String, found: Int, total: Int, what: String) =
        "$patch: $name. The patch goes on with the $found of $total $what it found."

    @Test
    fun `Block background ad prefetch blocks the schedulers a build has and names the rest`() {
        val kept = AD_PREFETCH_SCHEDULERS.take(2)
        // The third is there under its name but has no void method left, which blocks nothing.
        val emptied = AD_PREFETCH_SCHEDULERS[2]
        val context = PatchContexts.of(kept.map { scheduler(it) } + scheduler(emptied, withVoidMethod = false) + settingsStatus)

        val warnings = PatchLogCapture.warnings { blockAdPrefetchPatch.execute(context) }

        kept.forEach { type ->
            assertEquals("$type's void method returns at once", Opcode.RETURN_VOID, context.firstOpcode(type, "schedule"))
            assertEquals("$type's constructor is left alone", Opcode.INVOKE_STATIC, context.firstOpcode(type, "<init>"))
        }
        assertTrue("the patch applied, so its switch shows", context.statusOn("adPrefetch"))
        val patch = "Block background ad prefetch"
        val expected = listOf(missingLine(patch, "${javaName(emptied)} has no void method left to stop", 2, 7, "ad prefetch schedulers")) +
            AD_PREFETCH_SCHEDULERS.drop(3).map {
                missingLine(patch, "${javaName(it)} isn't in this Facebook build", 2, 7, "ad prefetch schedulers")
            }
        assertEquals(expected, warnings)
    }

    @Test
    fun `Block ad telemetry stops the classes a build has and names the rest`() {
        val kept = AD_TELEMETRY.drop(2)
        val context = PatchContexts.of(kept.map { scheduler(it) } + settingsStatus)

        val warnings = PatchLogCapture.warnings { blockAdTelemetryPatch.execute(context) }

        assertEquals(Opcode.RETURN_VOID, context.firstOpcode(kept.single(), "schedule"))
        assertTrue(context.statusOn("adTelemetry"))
        assertEquals(
            AD_TELEMETRY.take(2).map {
                missingLine("Block ad telemetry", "${javaName(it)} isn't in this Facebook build", 1, 3, "ad telemetry classes")
            },
            warnings,
        )
    }

    /** The positive control: a build with every class writes nothing to the log. */
    @Test
    fun `a build with every scheduler and telemetry class logs nothing`() {
        val context = PatchContexts.of((AD_PREFETCH_SCHEDULERS + AD_TELEMETRY).map { scheduler(it) } + settingsStatus)
        val warnings = PatchLogCapture.warnings {
            blockAdPrefetchPatch.execute(context)
            blockAdTelemetryPatch.execute(context)
        }
        assertEquals(emptyList<String>(), warnings)
        (AD_PREFETCH_SCHEDULERS + AD_TELEMETRY).forEach { assertEquals(Opcode.RETURN_VOID, context.firstOpcode(it, "schedule")) }
    }

    @Test
    fun `a build with none of the schedulers or telemetry classes stops each patch, naming every one`() {
        val context = PatchContexts.of(listOf(settingsStatus))
        for ((patch, list) in listOf(blockAdPrefetchPatch to AD_PREFETCH_SCHEDULERS, blockAdTelemetryPatch to AD_TELEMETRY)) {
            val refused = assertThrows(PatchException::class.java) { patch.execute(context) }
            val message = refused.message.orEmpty()
            list.forEach { assertTrue(message, message.contains("${javaName(it)} isn't in this Facebook build")) }
        }
        assertTrue("neither refused patch flipped its switch", !context.statusOn("adPrefetch") && !context.statusOn("adTelemetry"))
    }

    private fun manifest(vararg components: Pair<String, String>): Document {
        val entries = components.joinToString("\n") { (tag, name) -> """    <$tag android:name="$name" android:exported="true"/>""" }
        val text = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.facebook.katana">
              <application>
            $entries
                <activity android:name="com.facebook.katana.LoginActivity"/>
              </application>
            </manifest>
        """.trimIndent()
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
    }

    private fun Document.enabled(name: String): String? {
        for (tag in listOf("activity", "service")) {
            val nodes = getElementsByTagName(tag)
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                if (element.getAttribute("android:name") == name) return element.getAttribute("android:enabled")
            }
        }
        return null
    }

    /** The values of [name]'s default-state meta-data, which keeps Facebook from turning it back on (#168). */
    private fun Document.defaultStates(name: String): List<String> {
        val nodes = getElementsByTagName("meta-data")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { (it.parentNode as Element).getAttribute("android:name") == name && it.getAttribute("android:name") == "default-state" }
            .map { it.getAttribute("android:value") }
    }

    @Test
    fun `Disable Audience Network shuts the components a manifest has and names the rest`() {
        val service = AUDIENCE_NETWORK_COMPONENTS[0]
        val activity = AUDIENCE_NETWORK_COMPONENTS[3]
        val document = manifest("service" to service, "activity" to activity)

        val warnings = PatchLogCapture.warnings { disableAudienceNetwork(document) }

        assertEquals("false", document.enabled(service))
        assertEquals("false", document.enabled(activity))
        assertEquals("Facebook's own activity keeps its state", "", document.enabled("com.facebook.katana.LoginActivity"))
        assertEquals(listOf("false"), document.defaultStates(service))
        assertEquals(listOf("false"), document.defaultStates(activity))
        assertEquals(emptyList<String>(), document.defaultStates("com.facebook.katana.LoginActivity"))
        assertEquals(
            listOf(1, 2, 4).map {
                missingLine(
                    "Disable Audience Network", "${AUDIENCE_NETWORK_COMPONENTS[it]} isn't in the manifest", 2, 5,
                    "Audience Network components",
                )
            },
            warnings,
        )

        val all = manifest(*AUDIENCE_NETWORK_COMPONENTS.map { (if (it.endsWith("Service")) "service" else "activity") to it }.toTypedArray())
        assertEquals("the positive control logs nothing", emptyList<String>(), PatchLogCapture.warnings { disableAudienceNetwork(all) })
        AUDIENCE_NETWORK_COMPONENTS.forEach { assertEquals(it, "false", all.enabled(it)) }
        disableAudienceNetwork(all)
        AUDIENCE_NETWORK_COMPONENTS.forEach { assertEquals("$it, patched twice", listOf("false"), all.defaultStates(it)) }

        val refused = assertThrows(PatchException::class.java) { disableAudienceNetwork(manifest()) }
        AUDIENCE_NETWORK_COMPONENTS.forEach { assertTrue(refused.message, refused.message.orEmpty().contains("$it isn't in the manifest")) }
    }

    private fun unit(type: String): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, emptyList())

    @Test
    fun `Hide suggested and promoted posts goes on with the unit classes a build has and names the rest`() {
        val kept = SUGGESTED_FEED_UNITS.filterIndexed { index, _ -> index % 5 == 0 }
        val context = PatchContexts.of(kept.map(::unit))

        var infos = emptyList<String>()
        val warnings = PatchLogCapture.warnings { infos = PatchLogCapture.infos { context.requireSuggestedUnits() } }

        val total = SUGGESTED_FEED_UNITS.size
        val missing = SUGGESTED_FEED_UNITS - kept.toSet()
        val dropped = missing.filter { it in DROPPED_SUGGESTED_FEED_UNITS }
        assertTrue("the sample lacks some of each kind", dropped.isNotEmpty() && dropped.size < missing.size)
        assertEquals(
            (missing - dropped.toSet()).map {
                missingLine(
                    "Hide suggested and promoted posts", "${javaName(it)} isn't in this Facebook build", kept.size, total,
                    "suggested feed unit classes",
                )
            },
            warnings,
        )
        assertEquals(
            listOf(
                "Hide suggested and promoted posts: ${dropped.size} of the $total suggested feed unit classes left Facebook " +
                    "before ${AppCompatibilities.FACEBOOK_TARGET_VERSION}, which is expected. The patch goes on with the ${kept.size} it found.",
            ),
            infos,
        )

        // The declared build's own case: the six dropped units missing and nothing else is one plain line, no warning.
        val declared = PatchContexts.of((SUGGESTED_FEED_UNITS - DROPPED_SUGGESTED_FEED_UNITS).map(::unit))
        val declaredWarnings = PatchLogCapture.warnings { infos = PatchLogCapture.infos { declared.requireSuggestedUnits() } }
        assertEquals(emptyList<String>(), declaredWarnings)
        assertEquals(
            listOf(
                "Hide suggested and promoted posts: 6 of the $total suggested feed unit classes left Facebook before " +
                    "${AppCompatibilities.FACEBOOK_TARGET_VERSION}, which is expected. The patch goes on with the ${total - 6} it found.",
            ),
            infos,
        )

        assertEquals(
            "the positive control logs nothing",
            emptyList<String>(),
            PatchLogCapture.warnings { infos = PatchLogCapture.infos { PatchContexts.of(SUGGESTED_FEED_UNITS.map(::unit)).requireSuggestedUnits() } },
        )
        assertEquals(emptyList<String>(), infos)
        val refused = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).requireSuggestedUnits() }
        assertTrue(refused.message, refused.message.orEmpty().contains("none of the $total suggested feed unit classes"))
    }
}
