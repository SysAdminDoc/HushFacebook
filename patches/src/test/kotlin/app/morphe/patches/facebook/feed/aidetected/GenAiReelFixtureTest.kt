/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.aidetected

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.TREE_JNI
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Reels side of Hide AI-detected posts, found in the Facebook builds the bundle declares, the
 * way the patch finds it.
 *
 * Every declared build has to hand the attribution's type name to exactly one static finder, with
 * the compiled extension's own holders of the name searched beside Facebook's as the patcher sees
 * them, the finder has to pick by getTypeName(), Facebook's own label decision has to read the
 * detected flag on the attribution it answers, and TreeJNI has to keep the public readers the
 * extension uses. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class GenAiReelFixtureTest {
    @Test
    fun `every declared build has one attribution finder, whose answer the reel label reads the flag on`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val extensionHolders = ExtensionDex.classes().flatMap { methodsHolding(it, TRANSPARENCY_ATTRIBUTION) }
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val (holders, resolve) = reelLabelHolders(bundle)
                assertTrue("${bundle.name}: only ${holders.size} methods hold \"$TRANSPARENCY_ATTRIBUTION\"", holders.size >= 2)

                // The patcher searches the app with the extension merged in, and the reel filter
                // hands the same literal to its own stub, so the finder has to stay unique with the
                // extension's holders beside Facebook's.
                assertTrue("the extension no longer hands \"$TRANSPARENCY_ATTRIBUTION\" to anything, so the merged " +
                    "search below is no harder than Facebook's alone: drop this check", extensionHolders.isNotEmpty())
                val found = attributionFinder(holders + extensionHolders, resolve = resolve)
                assertNull("${bundle.name}: ${found.problem}", found.problem)
                val finder = found.call!!
                val finderClass = FixtureDex.classes(bundle, setOf(finder.definingClass))[finder.definingClass]
                    ?: throw AssertionError("${bundle.name} has no ${finder.definingClass}")
                val method = resolveStatic(finderClass, finder)
                    ?: throw AssertionError("${bundle.name}: ${finder.definingClass} declares no static ${finder.name}")
                assertTrue("${bundle.name}: ${finder.definingClass}->${finder.name} doesn't pick by getTypeName()",
                    isAttributionFinder(method))

                // The control: the finder's siblings taking the same parameters answer text or
                // something else, and none of them passes for the finder.
                val siblings = finderClass.methods.filter {
                    it.name != finder.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
                        it.parameterTypes.map { p -> p.toString() } == finder.parameterTypes.map { p -> p.toString() }
                }
                assertTrue("${bundle.name}: ${finder.definingClass} has no sibling taking (model, String) to hold this against",
                    siblings.isNotEmpty())
                for (sibling in siblings) {
                    assertFalse("${bundle.name}: ${sibling.name} passes for the finder too", isAttributionFinder(sibling))
                }

                val modelType = finder.parameterTypes[0].toString()
                val attributionType = finder.returnType
                val decisions = FixtureDex.methodsWhere(
                    bundle,
                    dexFilter = { dex -> dex.typeSection.contains(attributionType) && dex.typeSection.contains(modelType) },
                    wanted = { readsReelDetectedFlag(it, attributionType, modelType) },
                )
                // Up to 585 one method read it. 586 walks the model's attributions for the flag in
                // two places; the patch only needs one to know the flag is still where it reads it.
                assertEquals("${bundle.name}: the label decisions reading $DETECTED_FLAG on $attributionType: " +
                    decisions.map { "${it.definingClass}->${it.name}" }, 2, decisions.size)

                val tree = FixtureDex.classes(bundle, setOf(TREE_JNI))[TREE_JNI]
                    ?: throw AssertionError("${bundle.name} has no $TREE_JNI")
                assertTrue("${bundle.name}: no public TreeJNI.$TREE_BOOLEAN_READER(int)", hasPublicIntReader(tree, TREE_BOOLEAN_READER))
                assertTrue("${bundle.name}: no public TreeJNI.$TREE_FIELD_CHECK(int)", hasPublicIntReader(tree, TREE_FIELD_CHECK))
                checked += version
            }
        }
        assertEquals("a declared build went unchecked", versions.toSet(), checked)
    }
}

/**
 * The methods naming [TRANSPARENCY_ATTRIBUTION] in [bundle], gathered the way the patch gathers
 * them: those loading it, and those asking a string table that holds it ([asksTableFor]), with
 * what reads those tables. Only the dex files referencing a table are walked for the second.
 */
internal fun reelLabelHolders(bundle: File): Pair<List<Method>, (MethodReference) -> Method?> {
    val loading = FixtureDex.classesHolding(bundle, TRANSPARENCY_ATTRIBUTION).flatMap { methodsHolding(it, TRANSPARENCY_ATTRIBUTION) }
    val tables = loading.filter(::isStringTable)
    val tableClasses = FixtureDex.classes(bundle, tables.map { it.definingClass }.toSet())
    val resolve = { call: MethodReference -> tableClasses[call.definingClass]?.let { resolveStatic(it, call) } }
    val entries = tableEntries(tables, TRANSPARENCY_ATTRIBUTION)
    if (entries.isEmpty()) return loading to resolve
    val asking = FixtureDex.methodsWhere(
        bundle,
        dexFilter = { dex -> dex.methodSection.any { reference -> tables.any { it == reference } } },
        wanted = { asksTableFor(it, entries) },
    )
    return (loading + asking) to resolve
}
