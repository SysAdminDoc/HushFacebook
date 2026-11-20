/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postdates

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The song and the time under a post's name (#533) on every Facebook build the bundle declares: the
 * method the song's subtitle piece looks its song up with, and the model field the time's piece reads.
 * Then the patch on them: the lookup answers no song while the switch is on, and the time text goes
 * through the extension right before it's stored. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SubtitlePiecesFixtureTest {
    /** Where one build keeps them: the song lookup and the time text field. */
    private val expected = mapOf(
        // 588, read 2026-11-20: StoryAttributionPlugin->A01 logs the song's impression after asking
        // A00, and LX/02oy;->A02 and LX/02xj;->A00 read the time from LX/02xl;->A0B.
        AppCompatibilities.FACEBOOK_TARGET_VERSION to mapOf(
            "arm64-v8a" to (
                "Lcom/facebook/feed/plugins/header/subtitle/impl/storyattribution/StoryAttributionPlugin;->A00(LX/03PL;)LX/0ZaQ;" to
                    "LX/02xl;->A0B:Ljava/lang/CharSequence;"
                ),
        ),
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.names(call: String) = (this as? ReferenceInstruction)?.reference?.toString() == call

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun inBuild(context: BytecodePatchContext, name: String) =
        (context.mutableClassDefBy(POST_DATES).methods.single { it.name == name }.code()[0] as NarrowLiteralInstruction).narrowLiteral

    @Test
    fun `each declared build hides the song and bolds the time through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val abi = name.substringAfter("-$version-").substringBefore(".apkm")
                val extension = ExtensionDex.classDef(POST_DATES)
                val holders = (FixtureDex.classesHolding(bundle, SONG_LOG) + FixtureDex.classesHolding(bundle, TIME_MISSING))
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }.associateBy { it.type }
                val lookup = PatchContexts.of(holders.values.toList() + extension).findSongLookup()
                val time = PatchContexts.of(holders.values.toList() + extension).findTimeText()
                val model = FixtureDex.classes(bundle, setOf(time.definingClass) - holders.keys)
                val classes = holders + model
                val lookupName = "${lookup.definingClass}->${lookup.name}(${lookup.parameterTypes.joinToString("")})${lookup.returnType}"
                val timeName = "${time.definingClass}->${time.name}:${time.type}"
                expected[version]?.get(abi)?.let {
                    assertEquals("$name: the song lookup and the time text", it, lookupName to timeName)
                }
                // Negative control: without the model that stores the time there's nothing to make bold.
                assertThrows("$name: the time was made bold without its model", PatchException::class.java) {
                    PatchContexts.of(holders.filterKeys { it != time.definingClass }.values.toList() + extension).boldTimes(time)
                }

                val owner = classes.getValue(time.definingClass)
                val writes = owner.methods.associateWith { method ->
                    method.code().withIndex().filter { (_, it) ->
                        it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
                            "${field.definingClass}->${field.name}:${field.type}" == timeName
                        }
                    }.map { it.index }
                }.filterValues { it.isNotEmpty() }
                assertTrue("$name: nothing stores the time", writes.isNotEmpty())
                val originalLookup = lookup.code()
                val originalWrites = writes.keys.associateWith { it.code() }

                val context = PatchContexts.of(classes.values.toList() + extension)
                val warnings = mutableListOf<String>()
                context.addSubtitlePieces { warnings += it }
                assertEquals("$name: warnings", emptyList<String>(), warnings)

                val patchedLookup = context.mutableClassDefBy(lookup.definingClass).methods.single {
                    it.name == lookup.name && it.parameterTypes == lookup.parameterTypes
                }.code()
                assertEquals("$name: five instructions for the early answer", originalLookup.size + 5, patchedLookup.size)
                assertTrue("$name: the lookup asks the extension first", patchedLookup[0].names(HIDE_SONG))
                assertEquals(Opcode.MOVE_RESULT to 0, patchedLookup[1].opcode to (patchedLookup[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: a no runs Facebook's lookup", Opcode.IF_EQZ, patchedLookup[2].opcode)
                assertEquals("$name: a yes answers no song", listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT),
                    listOf(patchedLookup[3].opcode, patchedLookup[4].opcode))
                assertEquals("$name: the rest is Facebook's", originalLookup.map { it.opcode }, patchedLookup.drop(5).map { it.opcode })

                for ((method, original) in originalWrites) {
                    val where = "$name: ${method.definingClass}->${method.name}"
                    val patched = context.mutableClassDefBy(method.definingClass).methods.single {
                        it.name == method.name && it.parameterTypes == method.parameterTypes
                    }.code()
                    val at = writes.getValue(method)
                    assertEquals("$where: two instructions per write", original.size + 2 * at.size, patched.size)
                    val hooks = at.sorted().flatMapIndexed { added, index ->
                        val hook = index + 2 * added
                        val register = (original[index] as TwoRegisterInstruction).registerA
                        assertTrue("$where: the time goes through the extension before it's stored", patched[hook].names(BOLD_TIME))
                        assertEquals("$where: the time's register handed over", register to 1,
                            (patched[hook] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
                        assertEquals("$where: the extension's answer is what's stored", Opcode.MOVE_RESULT_OBJECT to register,
                            patched[hook + 1].opcode to (patched[hook + 1] as OneRegisterInstruction).registerA)
                        assertEquals(Opcode.IPUT_OBJECT, patched[hook + 2].opcode)
                        listOf(hook, hook + 1)
                    }.toSet()
                    assertEquals("$where: the rest is Facebook's", original.map { it.opcode },
                        patched.filterIndexed { index, _ -> index !in hooks }.map { it.opcode })
                }
                assertEquals("$name: PostDates.songsInBuild() isn't switched on", 1, inBuild(context, "songsInBuild"))
                assertEquals("$name: PostDates.timesInBuild() isn't switched on", 1, inBuild(context, "timesInBuild"))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
