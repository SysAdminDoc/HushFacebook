/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.carousel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.addressed
import app.morphe.patches.facebook.feed.tableIndices
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Photo carousel on every declared build: the album layouts' name table names the swipeable row
 * once and the three layouts a poster picks, the check of whether a layout applies starts by asking
 * the extension about the layout's name and answers no when it says so, and the row's feed check
 * hands its answer to the extension before Facebook tests it. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class PhotoCarouselFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    /** The layouts a poster picks, which the extension passes over by these ends of their names. */
    private val posterLayouts = listOf(".PhotoLayoutColumnsPlugin", ".PhotoLayoutBannerPlugin", ".PhotoLayoutFramePlugin")

    @Before
    @After
    fun forgetTheLastMatch() {
        AlbumLayoutNamesFingerprint.clearMatch()
        CarouselPhotoFingerprint.clearMatch()
    }

    private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun holds(method: Method, string: String) = method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    private fun isNameTable(method: Method) = method.returnType == "Ljava/lang/String;" &&
        method.parameterTypes.map(CharSequence::toString) == listOf("I") &&
        AccessFlags.STATIC.isSet(method.accessFlags) && holds(method, CAROUSEL_PLUGIN)

    /** Every address a branch or a switch in [method] goes to. */
    private fun branchTargets(method: Method): Set<Int> {
        val byAddress = addressed(method)
        val targets = HashSet<Int>()
        for ((address, instruction) in byAddress) {
            if (instruction is SwitchPayload) continue
            if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
                val payload = byAddress[address + (instruction as OffsetInstruction).codeOffset] as SwitchPayload
                payload.switchElements.mapTo(targets) { address + it.offset }
            } else if (instruction is OffsetInstruction) {
                targets += address + instruction.codeOffset
            }
        }
        return targets
    }

    private fun withoutNops(code: List<Instruction>) = code.map { it.opcode }.filter { it != Opcode.NOP }

    @Test
    fun `each declared build lets the swipeable row take a post's photos`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                forgetTheLastMatch()
                val name = bundle.name

                val tables = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == CAROUSEL_PLUGIN } }, ::isNameTable)
                assertEquals("$name: the album layouts' name table", 1, tables.size)
                val table = tables.single()
                for (layout in posterLayouts) {
                    val strings = table.code().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                    assertEquals("$name: the table names the poster's layout $layout once", 1, strings.count { it.endsWith(layout) })
                }

                val photos = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == CAROUSEL_PHOTO } }) {
                    it.name == "render" && holds(it, CAROUSEL_PHOTO) && holds(it, "multi_media_photo")
                }
                assertEquals("$name: the row's photo", 1, photos.size)
                val photo = photos.single().definingClass

                val classes = FixtureDex.classes(bundle, setOf(table.definingClass, photo, RECYCLER_VIEW))
                val holder = classes.getValue(table.definingClass)
                val rowDraw = classes.getValue(RECYCLER_VIEW).methods.single(::isRowDraw)
                val drawBefore = rowDraw.code()
                val layouts = albumLayouts(table, holder)
                val original = layouts.applies
                val before = original.code()
                val register = layouts.register
                val key = tableIndices(table, CAROUSEL_PLUGIN).single()
                assertTrue("$name: the row's feed check comes after its case starts", layouts.feedAnswer > 0)
                val check = before[layouts.feedAnswer - 1].methodRef()!!
                assertEquals("$name: the feed check answers a boolean", "Z", check.returnType)
                // Nothing branches onto the test of the answer, so every way to it passes the hook.
                val testAddress = addressed(original).keys.elementAt(layouts.feedAnswer + 1)
                assertFalse("$name: a branch goes straight to the test of the feed check's answer (row is case $key)",
                    testAddress in branchTargets(original))

                val context = PatchContexts.of(classes.values +
                    listOf(ExtensionDex.classDef(PHOTO_CAROUSEL), ExtensionDex.classDef(SETTINGS_STATUS)))
                photoCarouselPatch.execute(context)

                val patched = context.mutableClassDefBy(holder.type).methods.single { MethodUtil.methodSignaturesMatch(it, original) }
                val after = patched.code()

                // ---- the start: the layout's name to the extension, no when it passes it over ------
                val nameCall = after[0] as RegisterRangeInstruction
                assertEquals("$name: the layout's name from the table", "${table.definingClass}->${table.name}(I)Ljava/lang/String;",
                    after[0].methodRef().toString())
                assertEquals("$name: the table is asked for the layout being checked", listOf(original.parameterRegisterNumber(1), 1),
                    listOf(nameCall.startRegister, nameCall.registerCount))
                assertEquals(Opcode.MOVE_RESULT_OBJECT, after[1].opcode)
                assertEquals("$name: then the extension", SKIP_LAYOUT, after[2].methodRef().toString())
                assertEquals(Opcode.MOVE_RESULT, after[3].opcode)
                assertEquals(Opcode.IF_EQZ, after[4].opcode)
                val patchedAddresses = addressed(patched).keys.toList()
                assertEquals("$name: a layout it keeps goes on to Facebook's check",
                    patchedAddresses[7], patchedAddresses[4] + (after[4] as OffsetInstruction).codeOffset)
                assertEquals("$name: one it passes over answers no", 0, (after[5] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals(Opcode.RETURN, after[6].opcode)

                // ---- the row's feed check: its answer to the extension, then Facebook's test --------
                val answer = layouts.feedAnswer + 7
                assertEquals("$name: the feed check's answer is taken where it was", Opcode.MOVE_RESULT, after[answer].opcode)
                val gate = after[answer + 1] as RegisterRangeInstruction
                assertEquals("$name: the extension gets the answer", LET_CAROUSEL_IN, after[answer + 1].methodRef().toString())
                assertEquals(listOf(register, 1), listOf(gate.startRegister, gate.registerCount))
                assertEquals("$name: and gives it back in the same register", register, (after[answer + 2] as OneRegisterInstruction).registerA)
                assertEquals(Opcode.MOVE_RESULT, after[answer + 2].opcode)
                assertEquals("$name: then Facebook tests it", Opcode.IF_EQZ, after[answer + 3].opcode)

                // ---- the rest is Facebook's -----------------------------------------------------
                val ours = (0..6).toSet() + setOf(answer + 1, answer + 2)
                assertEquals("$name: Facebook's own code is unchanged", withoutNops(before),
                    withoutNops(after.filterIndexed { index, _ -> index !in ours }))

                // ---- the count: the photo's class name to the extension, the row's draw to it -----
                val stub = context.mutableClassDefBy(PHOTO_CAROUSEL).methods.single { it.name == "photoClass" }.code()
                assertEquals("$name: photoClass() answers the row's photo", javaName(photo),
                    ((stub[0] as ReferenceInstruction).reference as StringReference).string)
                assertEquals(Opcode.RETURN_OBJECT, stub[1].opcode)

                val drawAfter = context.mutableClassDefBy(RECYCLER_VIEW).methods.single(::isRowDraw).code()
                val returns = drawAfter.indices.filter { drawAfter[it].opcode == Opcode.RETURN_VOID }
                assertTrue("$name: RecyclerView.draw returns", returns.isNotEmpty())
                for (at in returns) {
                    val call = drawAfter[at - 1] as RegisterRangeInstruction
                    assertEquals("$name: the count is drawn right before each return", DRAW_COUNT, drawAfter[at - 1].methodRef().toString())
                    assertEquals("$name: handed the row and its canvas", listOf(rowDraw.implementation!!.registerCount - 2, 2),
                        listOf(call.startRegister, call.registerCount))
                }
                assertEquals("$name: the rest of RecyclerView.draw is androidx's", withoutNops(drawBefore),
                    withoutNops(drawAfter.filterIndexed { index, _ -> index + 1 !in returns }))

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "photoCarousel" }
                assertEquals("$name: SettingsStatus.photoCarousel() isn't switched on", 1,
                    (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
