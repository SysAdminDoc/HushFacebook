/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.Fixtures
import app.morphe.RepoFiles
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStringTableCall
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every anchor Clean up Reels needs, found in every Facebook build the bundle declares the way the
 * patch finds it, each with the registers its hook borrows, and each picked out of lookalikes the
 * same literal or name also finds. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class ReelAnchorsFixtureTest {
    private class Found {
        val builders = mutableListOf<Method>()
        var chipAnchorHolders = 0
        /** The static ImmutableList methods of the classes holding the chip anchor, before their strings are read. */
        val chipCandidates = mutableListOf<Method>()
        var immutableList: ClassDef? = null
        val dumpers = mutableListOf<Method>()
        val hotComments = mutableListOf<Method>()
        val mfcHotComments = mutableListOf<Method>()
        val bubbles = mutableListOf<Method>()
        var footerHelperClasses = 0
    }

    private val wanted = setOf(CHIP_ANCHOR, WATCH_FEED_DUMP, INLINE_COMMENTS_QUERY, MFC_INLINE_COMMENTS_QUERY, VIDEO_ID)

    /** How many MFC hot comment runnables each version has: 586 added the one a flag picks over the plain query's. */
    private val mfcHotCommentRunnables = mapOf("586.0.0.52.53" to 1)

    /** One pass over the bundle for everything but the Follow getter's readers. */
    private fun scan(bundle: File): Found {
        val found = Found()
        FixtureDex.forEach(bundle) { dex ->
            val present = dex.stringSection.filterTo(HashSet()) { it in wanted }
            for (classDef in dex.classes) {
                if (classDef.type == IMMUTABLE_LIST) found.immutableList = ImmutableClassDef.of(classDef)
                if (redexOriginalName(classDef)?.startsWith("FbShortsViewerFooter") == true) found.footerHelperClasses++
                if (CHIP_ANCHOR in present) {
                    val holding = classDef.methods.count { holdsString(it, CHIP_ANCHOR) }
                    found.chipAnchorHolders += holding
                    if (holding > 0) {
                        classDef.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == IMMUTABLE_LIST }
                            .mapTo(found.chipCandidates) { ImmutableMethod.of(it) }
                    }
                }
                if (WATCH_FEED_DUMP in present) {
                    classDef.methods.filter { holdsString(it, WATCH_FEED_DUMP) }.mapTo(found.dumpers) { ImmutableMethod.of(it) }
                }
                if (INLINE_COMMENTS_QUERY in present) {
                    footerRunnable(classDef, HOT_COMMENT_RUNNABLE, INLINE_COMMENTS_QUERY)?.let { found.hotComments += ImmutableMethod.of(it) }
                }
                if (MFC_INLINE_COMMENTS_QUERY in present) {
                    footerRunnable(classDef, HOT_COMMENT_RUNNABLE, MFC_INLINE_COMMENTS_QUERY)?.let { found.mfcHotComments += ImmutableMethod.of(it) }
                }
                if (VIDEO_ID in present) {
                    footerRunnable(classDef, SOCIAL_BUBBLES_RUNNABLE, VIDEO_ID)?.let { found.bubbles += ImmutableMethod.of(it) }
                }
            }
        }
        // The builder can ask a string table for a chip (582's armeabi-v7a one does), so the tables
        // the candidates call are read before the builder is picked, the way the patch reads them.
        val tableOwners = found.chipCandidates.flatMap { method ->
            method.implementation?.instructions?.toList().orEmpty().filter(::isStringTableCall)
                .mapNotNull { ((it as ReferenceInstruction).reference as? MethodReference)?.definingClass }
        }.toSet()
        val tables = FixtureDex.classes(bundle, tableOwners)
        found.chipCandidates.filterTo(found.builders) { candidate ->
            isChipListBuilder(candidate) { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
        }
        return found
    }

    /** Looks up a class of [bundle] by type, each one read once, for the dump's 583 trace. */
    private fun classesOf(bundle: File): (String) -> ClassDef? {
        val cache = mutableMapOf<String, ClassDef?>()
        return { type -> cache.getOrPut(type) { FixtureDex.classes(bundle, setOf(type))[type] } }
    }

    /** Every method calling [getter], and the getter's own class. Only dex files referencing it are walked. */
    private fun readers(bundle: File, getter: MethodReference): Pair<List<Method>, ClassDef?> {
        val readers = mutableListOf<Method>()
        var owner: ClassDef? = null
        FixtureDex.forEach(bundle) { dex ->
            val defines = dex.classes.firstOrNull { it.type == getter.definingClass }
            if (defines != null) owner = ImmutableClassDef.of(defines)
            if (dex.methodSection.none { it == getter }) return@forEach
            for (classDef in dex.classes) {
                classDef.methods.filter { calls(it, getter) }.mapTo(readers) { ImmutableMethod.of(it) }
            }
        }
        return readers to owner
    }

    private fun locals(method: Method): Int {
        val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        val parameters = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
        return method.implementation!!.registerCount - self - parameters
    }

    @Test
    fun `every declared build has each anchor once, with registers for its hook`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val found = scan(bundle)

                // The chip list builder, one of the many methods naming the Remix chip.
                assertEquals("$name: ${found.builders}", 1, found.builders.size)
                assertTrue("$name: only ${found.chipAnchorHolders} methods hold \"$CHIP_ANCHOR\"", found.chipAnchorHolders >= 10)
                val builder = found.builders.single()
                val returns = builder.implementation!!.instructions.count { it.opcode == Opcode.RETURN_OBJECT }
                assertTrue("$name: the builder returns nothing", returns >= 1)
                assertTrue("$name: the builder has ${locals(builder)} locals", locals(builder) >= 2)
                assertTrue("$name: ImmutableList has no copyOf(Object[])", hasCopyOfArray(requireNotNull(found.immutableList) { "$name has no ImmutableList" }))

                // The Follow getter, traced from each dumped "removeFollowingButton" value.
                assertEquals("$name: ${found.dumpers}", 1, found.dumpers.size)
                val dumper = found.dumpers.single()
                val follow = followButtonGetter(dumper, classesOf(bundle))
                assertNull("$name: ${follow.problem}", follow.problem)
                val getter = follow.getter
                if (getter == null) {
                    // 583 to 586: two flag reads in merged lambdas (one class each on 585), hooked in each lambda's
                    // invoke() by key, which needs two locals there and the field the switch reads the key from.
                    assertEquals("$name: ${follow.flags}", 2, follow.flags.size)
                    val lambdas = FixtureDex.classes(bundle, follow.flags.map { it.lambda }.toSet())
                    for (flag in follow.flags) {
                        val lambda = lambdas.getValue(flag.lambda)
                        val invoke = lambda.methods.single {
                            it.name == "invoke" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Object;"
                        }
                        assertTrue("$name: ${flag.lambda}'s invoke() has ${locals(invoke)} locals", locals(invoke) >= 2)
                        assertEquals("$name: ${flag.lambda}'s key field", "\$t", lambdaKeyField(lambda)?.name)
                    }
                    // Redex names differ per ABI: the lambda classes and their keys, then the method each flag is read in.
                    val abi = name.substringAfter("-$version-").substringBefore(".apkm")
                    val pinned = mapOf(
                        "585.0.0.47.54 arm64-v8a" to "LX/0ACw;:155:A1Z LX/0ACj;:107:A0F",
                        "585.0.0.47.54 armeabi-v7a" to "LX/0AD1;:155:A1Z LX/0ACo;:105:A0F",
                        // Android 9: the dump makes the first lambda and the helper the second, each flag read in its first switch.
                        "585.0.0.47.54 arm64-v8a-minapi28-storeless" to "LX/0cLU;:10:invoke LX/0ADL;:157:invoke",
                        "586.0.0.52.53 arm64-v8a" to "LX/0A3w;:134:A18 LX/0A3h;:198:A0Q",
                        "586.0.0.52.53 armeabi-v7a" to "LX/0A3w;:134:A18 LX/0A3h;:198:A0Q",
                        "586.0.0.52.53 arm64-v8a-minapi28-storeless" to "LX/0A5H;:52:A0A LX/0A4q;:118:invoke",
                    )["$version $abi"]
                    assertEquals(name, pinned, follow.flags.joinToString(" ") { "${it.lambda}:${it.key}:${it.reader.name}" })
                } else {
                    val (readers, owner) = readers(bundle, getter)
                    assertNull("$name: readers", followReaderProblem(readers, dumper))
                    val method = requireNotNull(owner) { "$name has no ${getter.definingClass}" }.methods.single { it.name == getter.name && it.returnType == "Z" && it.parameterTypes.isEmpty() }
                    assertTrue("$name: ${getter.name} is static", !AccessFlags.STATIC.isSet(method.accessFlags))
                    assertTrue("$name: ${getter.name} has no local for the hook", locals(method) >= 1)
                }

                // Both footer runnables, among the footer helpers' other kept-name classes.
                assertEquals("$name: ${found.hotComments}", 1, found.hotComments.size)
                assertEquals("$name: ${found.mfcHotComments}", mfcHotCommentRunnables[version] ?: 0, found.mfcHotComments.size)
                assertEquals("$name: ${found.bubbles}", 1, found.bubbles.size)
                assertTrue("$name: ${found.footerHelperClasses} footer helper classes", found.footerHelperClasses >= 3)
                for (run in found.hotComments + found.mfcHotComments + found.bubbles) {
                    assertTrue("$name: $run has no local for the hook", locals(run) >= 1)
                }
                checked[version] = "${follow.literals} Follow literal(s), " +
                    (getter?.let { "getter ${it.definingClass}->${it.name}" } ?: "flags ${follow.flags.joinToString { "${it.lambda}:${it.key}" }}")
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    /**
     * The Follow check, found the way the patch finds it: from the dump to the Following getter, to
     * its readers, to the author row among them, to the one check that row asks beside the Follow
     * button it builds. The getter alone missed the Reels tab on 0.1.7, since the row reads it only
     * for an author you already follow.
     */
    @Test
    fun `every declared build's author row asks one Follow check, with a local for its hook`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val dumpers = FixtureDex.classesHolding(bundle, WATCH_FEED_DUMP).flatMap { methodsHolding(it, WATCH_FEED_DUMP) }
                assertEquals("$name: $dumpers", 1, dumpers.size)
                val dumper = dumpers.single()
                val follow = followButtonGetter(dumper, classesOf(bundle))
                val getter = follow.getter
                val author = if (getter == null) {
                    // 583 and 584 read the Following button's config through flags, so the row is found by its own literal.
                    assertTrue("$name: the dump names no Following getter or flags", follow.flags.isNotEmpty())
                    FixtureDex.classesHolding(bundle, AUTHOR_COMPONENT).flatMap { methodsHolding(it, AUTHOR_COMPONENT) }.single()
                } else {
                    val (readers, _) = readers(bundle, getter)
                    requireNotNull(authorRow(readers, dumper)) { "$name: no author row among $readers" }
                }

                val owners = FixtureDex.classes(bundle, staticOrVirtualCalls(author).map { it.definingClass }.toSet())
                val asked = followCheck(author) { call -> owners[call.definingClass]?.let { resolveCalled(it, call) } }
                assertNull("$name: ${asked.problem}", asked.problem)
                val check = asked.check!!
                assertTrue("$name: ${check.name} has no local for the hook", locals(check) >= 1)
                checked[version] = "${check.definingClass}->${check.name}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    /**
     * The Reels tab's audio line row (#200), found the way the patch finds it: the one `render` that
     * picks the soundtrack model before it can return and answers null first, out of the renders
     * holding the same name. The chip filter alone left the line there on 583.
     */
    @Test
    fun `every declared build has one Reels tab audio line row, with a local for its hook`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, SOUNDTRACK_CHIP)
                val renders = holders.flatMap { classDef -> classDef.methods.filter { it.name == RENDER && holdsString(it, SOUNDTRACK_CHIP) } }
                assertTrue("$name: only ${renders.size} renders hold \"$SOUNDTRACK_CHIP\"", renders.size >= 2)
                val rows = holders.flatMap { classDef -> classDef.methods.filter(::isAudioLineRender) }
                assertEquals("$name: $rows", 1, rows.size)
                assertTrue("$name: the row has no local for the hook", locals(rows.single()) >= 1)
                checked[version] = rows.single().definingClass
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    /**
     * "Made with Edits" under a caption (#314), found the way the patch finds it: the one `render`
     * reading the video's Edits flag and nothing else from the tree. The flag's other readers are
     * static helpers.
     */
    @Test
    fun `every declared build has one Made with Edits line, with a local for its hook`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val lines = mutableListOf<Method>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        classDef.methods.filter(::isEditsLabelRender).mapTo(lines) { ImmutableMethod.of(it) }
                    }
                }
                assertEquals("$name: $lines", 1, lines.size)
                assertTrue("$name: the line has no local for the hook", locals(lines.single()) >= 1)
                checked[version] = lines.single().definingClass
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    @Test
    fun `the patch requires the chips the extension hides`() {
        val declutter = File(RepoFiles.root,
            "extensions/facebook/src/main/java/app/morphe/extension/facebook/reels/ReelDeclutter.java")
        val array = Regex("""HIDDEN_CHIPS\s*=\s*\{([^}]*)\}""").find(declutter.readText())
            ?: throw AssertionError("ReelDeclutter.java has no HIDDEN_CHIPS array")
        val hidden = Regex(""""([A-Za-z]+)"""").findAll(array.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(HIDDEN_CHIPS, hidden)
        // #200: the audio line the extension hides under its own switch is the one the builder must name.
        val soundtrack = Regex("""SOUNDTRACK_CHIP\s*=\s*"([A-Za-z]+)"""").find(declutter.readText())
            ?: throw AssertionError("ReelDeclutter.java has no SOUNDTRACK_CHIP")
        assertEquals(SOUNDTRACK_CHIP, soundtrack.groupValues[1])
    }
}
