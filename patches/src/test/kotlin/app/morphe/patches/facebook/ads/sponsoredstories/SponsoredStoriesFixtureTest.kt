/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredstories

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide sponsored stories on every Facebook build the bundle declares: the patch runs over the real
 * classes and each of the four bucket sources hands back the list it was given while the switch is
 * on. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SponsoredStoriesFixtureTest {
    private val feedFilter = "Lapp/morphe/extension/facebook/feed/FeedFilter;"
    private val switch = "$feedFilter->hideSponsoredStories()Z"

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun holders(classes: List<ClassDef>, string: String, wanted: (Method) -> Boolean) =
        classes.flatMap { owner -> owner.methods.filter { wanted(it) && holdsString(it, string) } }

    @Test
    fun `each declared build's four story ad sources pass the list through`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val pool = mutableListOf<ClassDef>()
                val sources = listOf(INORGANIC_CLASH, PAGINATING_BUCKETS, MID_CARD_BUCKETS).map { key ->
                    val owners = FixtureDex.classesHolding(bundle, key)
                    pool += owners
                    val found = holders(owners, key, ::isBucketSource)
                    assertEquals("$name: bucket sources holding \"$key\"", 1, found.size)
                    found.single()
                }.toMutableList()

                val owners = FixtureDex.classesHolding(bundle, UNINSERTED_ADS)
                pool += owners
                val placements = holders(owners, UNINSERTED_ADS) { it.returnType == JAVA_LIST }
                assertEquals("$name: ad placement helpers", 1, placements.size)
                val placement = placements.single()
                val engines = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.typeSection.any { it == placement.definingClass }
                }) { isBucketSource(it) && handsOffTo(it, placement) }
                assertEquals("$name: bucket sources calling the ad placement helper", 1, engines.size)
                sources += engines.single()
                pool += FixtureDex.classes(bundle, setOf(engines.single().definingClass)).values
                pool += ExtensionDex.classDef(SETTINGS_STATUS)

                // A fingerprint keeps its match, and each ABI is its own Redex output.
                listOf(
                    InorganicClashResolverFingerprint,
                    AdBucketPlacementFingerprint,
                    AdPaginatingBucketInserterFingerprint,
                    StoryViewerMidCardFingerprint,
                ).forEach { it.clearMatch() }
                val context = PatchContexts.of(pool.associateBy { it.type }.values)
                hideSponsoredStoriesPatch.execute(context)

                sources.forEach { stock ->
                    val where = "$name: ${stock.descriptor()}"
                    val patched = context.mutableClassDefBy(stock.definingClass).methods
                        .single { it.descriptor() == stock.descriptor() }
                    val code = patched.code()
                    assertEquals("$where: four instructions in front", stock.code().size + 4, code.size)
                    val asked = (code[0] as ReferenceInstruction).reference as MethodReference
                    assertEquals("$where asks the switch first", switch, asked.descriptor())
                    assertEquals("$where: the answer is read", Opcode.MOVE_RESULT, code[1].opcode)
                    assertEquals("$where: the answer decides", Opcode.IF_EQZ, code[2].opcode)
                    assertEquals("$where: the list goes back", Opcode.RETURN_OBJECT, code[3].opcode)
                    assertEquals("$where: the list is the last parameter",
                        patched.implementation!!.registerCount - 1, (code[3] as OneRegisterInstruction).registerA)
                }

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "sponsoredStories" }
                assertEquals("$name: SettingsStatus.sponsoredStories() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the switch`() {
        val found = ExtensionDex.classDef(feedFilter).methods.filter { it.name == "hideSponsoredStories" }
        assertEquals("FeedFilter.hideSponsoredStories", listOf(switch), found.map { it.descriptor() })
    }
}
