/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.reelspeed

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.media.resume.VIDEO_PLAYER_PARAMS
import app.morphe.patches.facebook.media.resume.reportedValues
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Speed on finished live videos on every Facebook build the bundle declares: one gear menu builder,
 * the method holding UNIFIED_PLAYER_VDD_ENGAGEMENT_STATE that casts to the speed row's click
 * handler, and in it one engagement test between a MobileConfig flag's if-nez and a goto onto the
 * speed row. The flag is a MobileConfig read or its cached copy, the tested register is set to 1
 * right after the engagement state compares equal, and the row's isSponsored register holds
 * VideoPlayerParams' isSponsored, as the params' debug dump reports it. Then the patch's hook, run
 * on those classes: four instructions in front of the test, both yeses jumping to the row, and
 * nothing else moved. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReplaySpeedFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    /** A goto keeps its kind unless the patcher had to widen it; the rest compare as they are. */
    private fun shape(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }
        .map { if (it.opcode == Opcode.GOTO || it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode }

    @Test
    fun `each declared build has one gear menu builder, and the hook goes in front of its engagement test`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val paramsClass = FixtureDex.classes(bundle, setOf(VIDEO_PLAYER_PARAMS)).values.single()
                val dumps = paramsClass.methods.filter { m -> REEL_PARAM_STUBS.keys.all { holdsString(m, it) } }
                assertEquals("$name: methods reporting ${REEL_PARAM_STUBS.keys}", 1, dumps.size)
                val sponsored: FieldReference = reportedValues(dumps.single())["isSponsored"]
                    ?: throw AssertionError("$name: the dump doesn't report isSponsored")

                val rows = FixtureDex.classesHolding(bundle, GEAR_SPEED_TAP).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val rowTypes = rows.filter { row -> row.methods.any { holdsString(it, GEAR_SPEED_TAP) } }.map { it.type }.toSet()
                val holders = FixtureDex.classesHolding(bundle, ENGAGEMENT_STATE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val builders = holders.flatMap { it.methods }.filter { holdsString(it, ENGAGEMENT_STATE) }.flatMap { method ->
                    method.code().filter { it.opcode == Opcode.CHECK_CAST }
                        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                        .filter { it in rowTypes }.distinct().map { method to it }
                }
                assertEquals("$name: builders holding \"$ENGAGEMENT_STATE\" casting to a speed row", 1, builders.size)
                val (builder, rowType) = builders.single()

                val site = replaySpeedSite(builder, rowType, sponsored)
                val code = builder.code()
                val flagTest = site.test - 1

                // The if-nez reads a MobileConfig flag, freshly read or cached in a boolean field.
                val flagWriters = builder.writersReaching(flagTest, site.scratch)
                assertTrue("$name: the flag at $flagTest has no writer", flagWriters.isNotEmpty())
                for (writer in flagWriters) {
                    val instruction = code[writer]
                    val mobileConfig = instruction.opcode == Opcode.MOVE_RESULT &&
                        ((code[writer - 1] as? ReferenceInstruction)?.reference as? MethodReference)
                            ?.definingClass?.contains("mobileconfig") == true
                    assertTrue("$name: the flag's writer at $writer is ${instruction.opcode}",
                        mobileConfig || instruction.opcode == Opcode.IGET_BOOLEAN)
                }

                // The tested register is set to 1 right after the engagement state compares equal.
                val compare = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == ENGAGEMENT_STATE }
                val engagedSet = (compare until minOf(compare + 8, code.size)).any { at ->
                    val instruction = code[at]
                    (instruction.opcode == Opcode.CONST_4 || instruction.opcode == Opcode.CONST_16) &&
                        (instruction as OneRegisterInstruction).registerA == site.engaged &&
                        (instruction as NarrowLiteralInstruction).narrowLiteral == 1
                }
                assertTrue("$name: v${site.engaged} isn't set to 1 after the engagement state compares", engagedSet)

                // The row's isSponsored test reads the field the dump reports, through one move at most.
                val rowTest = code[site.speedRow + 3]
                assertEquals("$name: the row's isSponsored test", site.sponsored, (rowTest as OneRegisterInstruction).registerA)
                val fed = builder.writersReaching(site.speedRow + 3, site.sponsored).all { writer ->
                    val instruction = code[writer]
                    val read = if (instruction.opcode == Opcode.IGET_BOOLEAN) instruction
                    else code[builder.writersReaching(writer, (instruction as TwoRegisterInstruction).registerB).single()]
                    ((read as ReferenceInstruction).reference as FieldReference).name == sponsored.name
                }
                assertTrue("$name: the row's isSponsored isn't ${sponsored.name}", fed)

                // The patch on those classes.
                val extension = ExtensionDex.classDef("Lapp/morphe/extension/facebook/media/ReplaySpeed;")
                val offer = extension.methods.single { it.name == "offerSpeed" }
                assertEquals("$name: offerSpeed's parameters", listOf("I"), offer.parameterTypes.map(CharSequence::toString))
                assertEquals("$name: offerSpeed's answer", "Z", offer.returnType)
                assertTrue("$name: offerSpeed isn't public static",
                    AccessFlags.PUBLIC.isSet(offer.accessFlags) && AccessFlags.STATIC.isSet(offer.accessFlags))
                val context = PatchContexts.of((rows + holders + listOf(extension)).distinctBy { it.type })
                context.offerReplaySpeed(sponsored)
                val patched = context.mutableClassDefBy(builder.definingClass).methods.single {
                    it.name == builder.name && it.returnType == builder.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                }
                val after = patched.code()
                val test = site.test
                assertEquals("$name: the hook", listOf(Opcode.IF_NEZ, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.IF_EQZ),
                    after.subList(test, test + 5).map { it.opcode })
                assertEquals("$name: the extension's question", OFFER_SPEED, (after[test + 1] as ReferenceInstruction).reference.toString())
                val call = after[test + 1] as RegisterRangeInstruction
                assertEquals("$name: the register handed over", site.sponsored to 1, call.startRegister to call.registerCount)
                assertEquals("$name: the registers the hook reads and writes", listOf(site.engaged, site.scratch, site.scratch, site.engaged),
                    listOf(test, test + 2, test + 3, test + 4).map { (after[it] as OneRegisterInstruction).registerA })

                val flow = ControlFlow.of(patched)
                val row = if (site.speedRow < test) site.speedRow else site.speedRow + 4
                assertEquals("$name: the row after the hook", Opcode.MOVE_RESULT_OBJECT, after[row + 1].opcode)
                assertEquals("$name: an engaged player's jump", row, flow.normal[test].first())
                assertEquals("$name: a yes's jump", row, flow.normal[test + 3].first())
                assertEquals("$name: Facebook's flag still jumps to the row", row, flow.normal[test - 1].first())
                assertEquals("$name: Facebook's goto still goes to the row", listOf(row), flow.normal[test + 5])
                assertEquals("$name: the builder's other instructions", shape(code),
                    shape(after.filterIndexed { index, _ -> index !in test until test + 4 }))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
