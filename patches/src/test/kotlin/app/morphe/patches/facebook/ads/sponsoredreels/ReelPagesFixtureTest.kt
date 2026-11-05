/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.reels.FOLLOWED_REEL_SECTION_FILTER
import app.morphe.patches.facebook.reels.THREADS_CARD_SECTION_FILTER
import app.morphe.patches.facebook.reels.filterSectionsWithControllerFirst
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The three methods a Reels page enters by, found in each declared build the way the patch finds
 * them, with both page filters put on each as the two patches put them, and Clean up Reels' Threads
 * card filter on the controller's page method too: the page copied down into
 * v0, the class name in v1, and the answer copied back. 577's controller page method has 15
 * registers, its page in v14, so two more would have put the page out of the filter call's reach.
 *
 * The second test covers the inserts 581's client-side Reels loader uses (#47): the one-item insert,
 * which drops an item both filters empty out, and the static append 580 and 581 have.
 */
class ReelPagesFixtureTest {
    @Before
    @After
    fun forgetMatches() {
        SfdAdInsertFingerprint.clearMatch()
        VideoHomeInsertAdsFingerprint.clearMatch()
    }

    /**
     * Registers and the page's register of the page insert, the announcement and the controller's
     * page method, by build and ABI. 585's arm64 controller page method has 11 registers (584's 13),
     * which still keeps its page in range of the filter call.
     */
    private val expected = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to listOf(6 to 5, 7 to 6, 11 to 10),
            "armeabi-v7a" to listOf(6 to 5, 7 to 6, 11 to 10),
        ),
    )

    private fun pin(bundle: java.io.File, version: String) =
        expected.getValue(version)[bundle.name.substringAfter("-$version-").substringBefore(".apkm")]
            ?: throw AssertionError("${bundle.name} has no pin")

    private fun MutableMethod.assertFilteredAtTop(where: String, page: Int, at: Int) {
        val body = implementation!!.instructions.toList()
        val copy = body[at] as TwoRegisterInstruction
        assertEquals("$where: copy down", listOf(Opcode.MOVE_OBJECT_FROM16, 0, page), listOf(body[at].opcode, copy.registerA, copy.registerB))
        val call = body[at + 2] as FiveRegisterInstruction
        assertEquals("$where: the filter call", listOf(0, 1), listOf(call.registerC, call.registerD))
        val back = body[at + 4] as TwoRegisterInstruction
        assertEquals("$where: copy back", listOf(Opcode.MOVE_OBJECT_16, page, 0), listOf(body[at + 4].opcode, back.registerA, back.registerB))
    }

    @Test
    fun `both page filters go on the three page methods of each declared build in v0 and v1`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertEquals("the declared builds", expected.keys, versions)
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetMatches()
                val anchors = FixtureDex.classesHolding(bundle, "VideoHomeDataControllerSfdAdsUtil") +
                    FixtureDex.classesHolding(bundle, "VideoHomeDataControllerImpl.maybeInsertAds")
                val taken = anchors.flatMap { it.methods }.filter { it.name == "<init>" }
                    .flatMap { method -> method.parameterTypes.map { it.toString() } }
                    .filter { it.startsWith("L") }.toSet()
                val classes = anchors + FixtureDex.classes(bundle, taken).values
                val context = PatchContexts.of(classes)

                val pages = with(context) { reelPages(SPONSORED_REELS_PATCH) }
                val methods = listOf(pages.insertPage, pages.announcePage, pages.addPage)
                assertEquals(
                    "${bundle.name}: registers and page register",
                    pin(bundle, version),
                    methods.map { it.implementation!!.registerCount to it.implementation!!.registerCount - 1 },
                )

                pages.insertPage.filterPageFirst("fixture.Ad")
                pages.announcePage.filterPageFirst("fixture.Ad")
                pages.addPage.filterSectionsFirst("fixture.Ad")
                // Hide AI-detected posts puts its own in front, on the same methods.
                pages.insertPage.filterPageFirst("fixture.Model", "Lfixture/Reels;->pages(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;")
                pages.announcePage.filterPageFirst("fixture.Model", "Lfixture/Reels;->pages(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;")
                pages.addPage.filterSectionsFirst("fixture.Model", "Lfixture/Reels;->sections(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;")
                // And Clean up Reels its Threads card filter, on the controller's page method only.
                pages.addPage.filterSectionsFirst("fixture.MidCard", THREADS_CARD_SECTION_FILTER, "Clean up Reels")

                methods.forEachIndexed { index, method ->
                    val page = pin(bundle, version)[index].second
                    val where = "${bundle.name}: ${method.definingClass}->${method.name}"
                    val first = if (method == pages.addPage) 5 else 0
                    if (method == pages.addPage) method.assertFilteredAtTop("$where, the Threads card filter", page, at = 0)
                    method.assertFilteredAtTop("$where, the AI filter", page, at = first)
                    method.assertFilteredAtTop("$where, the ad filter", page, at = first + 5)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Only reels from accounts I follow's section filter on the controller's page method, with the
     * controller in front so the extension can keep each viewer's first reel: `this` copied into v0,
     * the page into v1, the class name in v2, and the answer copied back into the page's register.
     */
    @Test
    fun `the followed reels section filter passes the controller in each declared build`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetMatches()
                val anchors = FixtureDex.classesHolding(bundle, "VideoHomeDataControllerSfdAdsUtil") +
                    FixtureDex.classesHolding(bundle, "VideoHomeDataControllerImpl.maybeInsertAds")
                val taken = anchors.flatMap { it.methods }.filter { it.name == "<init>" }
                    .flatMap { method -> method.parameterTypes.map { it.toString() } }
                    .filter { it.startsWith("L") }.toSet()
                val context = PatchContexts.of(anchors + FixtureDex.classes(bundle, taken).values)
                val addPage = with(context) { reelPages(SPONSORED_REELS_PATCH) }.addPage
                val page = pin(bundle, version)[2].second
                val where = "${bundle.name}: ${addPage.definingClass}->${addPage.name}"

                addPage.filterSectionsWithControllerFirst("fixture.Model", FOLLOWED_REEL_SECTION_FILTER)

                val body = addPage.implementation!!.instructions.toList()
                assertEquals(
                    "$where: the controller, the page and the answer",
                    listOf(
                        listOf(Opcode.MOVE_OBJECT_FROM16, 0, page - 1),
                        listOf(Opcode.MOVE_OBJECT_FROM16, 1, page),
                        listOf(Opcode.MOVE_OBJECT_16, page, 1),
                    ),
                    listOf(0, 1, 5).map { at ->
                        val move = body[at] as TwoRegisterInstruction
                        listOf(body[at].opcode, move.registerA, move.registerB)
                    },
                )
                val call = body[3] as FiveRegisterInstruction
                assertEquals("$where: the filter call", listOf(3, 0, 1, 2),
                    listOf(call.registerCount, call.registerC, call.registerD, call.registerE))
                assertEquals("$where: the filter", FOLLOWED_REEL_SECTION_FILTER,
                    ((body[3] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$where: the answer", listOf(Opcode.MOVE_RESULT_OBJECT, 1),
                    listOf(body[4].opcode, (body[4] as OneRegisterInstruction).registerA))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Registers and the item's register of the one-item insert, then the static append's, null where
     * a build has none, by build and ABI. 585's arm64 build has no static append: only the
     * one-item insert (and the page insert) feed the Reels list.
     */
    private val inserts = mapOf(
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to listOf(6 to 4, null),
            "armeabi-v7a" to listOf(6 to 4, null),
        ),
    )

    /** The one-item drop at [at]: the item copied into a one-item list in v0, the filter's answer asked if empty. */
    private fun MutableMethod.assertItemDropAt(where: String, item: Int, at: Int, next: Int) {
        val body = implementation!!.instructions.toList()
        val copy = body[at] as TwoRegisterInstruction
        assertEquals("$where: copy down", listOf(Opcode.MOVE_OBJECT_FROM16, 0, item), listOf(body[at].opcode, copy.registerA, copy.registerB))
        val call = body[at + 4] as FiveRegisterInstruction
        assertEquals("$where: the filter call", listOf(0, 1), listOf(call.registerC, call.registerD))
        assertEquals(
            "$where: the drop",
            listOf(Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
            body.subList(at + 6, at + 10).map { it.opcode },
        )
        assertEquals("$where: a kept item goes on", next, (body[at + 8] as BuilderOffsetInstruction).target.location.index)
    }

    @Test
    fun `the one-item insert and the static append each build adds the Reels tab's reels by carry both filters`() {
        val checked = mutableSetOf<String>()
        for ((version, byAbi) in inserts) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val shapes = byAbi[bundle.name.substringAfter("-$version-").substringBefore(".apkm")]
                    ?: throw AssertionError("${bundle.name} has no pin")
                forgetMatches()
                val anchors = FixtureDex.classesHolding(bundle, "VideoHomeDataControllerSfdAdsUtil") +
                    FixtureDex.classesHolding(bundle, "VideoHomeDataControllerImpl.maybeInsertAds")
                val taken = anchors.flatMap { it.methods }.filter { it.name == "<init>" }
                    .flatMap { method -> method.parameterTypes.map { it.toString() } }
                    .filter { it.startsWith("L") }.toSet()
                val context = PatchContexts.of(anchors + FixtureDex.classes(bundle, taken).values)
                val pages = with(context) { reelPages(SPONSORED_REELS_PATCH) }

                val item = shapes[0]!!
                val append = shapes[1]
                assertEquals("${bundle.name}: the one-item insert", item, pages.insertItem.implementation!!.let { it.registerCount to it.registerCount - 2 })
                assertEquals("${bundle.name}: the static append", append, pages.appendPage?.implementation?.let { it.registerCount to it.registerCount - 1 })
                assertEquals("${bundle.name}: the page inserts", if (append == null) 1 else 2, pages.pageInserts.size)

                // Hide sponsored reels first, then Hide AI-detected posts in front of it.
                pages.insertItem.dropItemFirst("fixture.Ad")
                pages.insertItem.dropItemFirst("fixture.Model", "Lfixture/Reels;->pages(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;")
                val where = "${bundle.name}: ${pages.insertItem.definingClass}->${pages.insertItem.name}"
                pages.insertItem.assertItemDropAt("$where, the AI drop", item.second, at = 0, next = 10)
                pages.insertItem.assertItemDropAt("$where, the ad drop", item.second, at = 10, next = 20)

                pages.appendPage?.let { method ->
                    method.filterPageFirst("fixture.Ad")
                    method.filterPageFirst("fixture.Model", "Lfixture/Reels;->pages(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;")
                    method.assertFilteredAtTop("${bundle.name}: the append, the AI filter", append!!.second, at = 0)
                    method.assertFilteredAtTop("${bundle.name}: the append, the ad filter", append.second, at = 5)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", inserts.keys, checked)
    }
}
