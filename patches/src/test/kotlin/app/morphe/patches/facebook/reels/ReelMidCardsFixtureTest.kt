/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reads from a Reels mid-card item to its type, found in every declared Facebook build the way
 * Clean up Reels finds them: the one enum naming THREADS_MIDCARD that a model answers, the model's
 * and the unit's getters, and the one reel item keeping the unit. Facebook's own Reels code has to
 * make the same reads on the same item, and the stub the patch fills has to assemble with them,
 * using its parameter register only. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class ReelMidCardsFixtureTest {
    /** Whether [method] picks out [item] by instance-of and asks a model for its type through [typeGetter]. */
    private fun readsTheType(method: Method, item: String, typeGetter: String): Boolean {
        var picks = false
        var reads = false
        for (instruction in method.implementation?.instructions ?: return false) {
            val reference = (instruction as? ReferenceInstruction)?.reference ?: continue
            if (instruction.opcode == Opcode.INSTANCE_OF && (reference as TypeReference).type == item) picks = true
            if (reference is MethodReference && reference.toString() == typeGetter) reads = true
        }
        return picks && reads
    }

    @Test
    fun `every declared build has one mid-card type chain, read by Facebook's own Reels code`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val scan = MidCardScan()
                FixtureDex.forEach(bundle) { dex -> dex.classes.forEach(scan::visit) }
                val resolved = scan.resolve()
                assertNull("$name: ${resolved.problem}", resolved.problem)
                val card = resolved.found!!

                val classes = FixtureDex.classes(bundle, setOf(card.item, card.unit, card.model, card.type))
                assertEquals("$name: the chain's classes", 4, classes.size)

                // The type is a GraphQL enum: Facebook's unknown-value constant beside the Threads card
                // and another mid-card, so it isn't a lookalike naming THREADS_MIDCARD for some other use,
                // and the interest survey Hide reel interest prompts takes off (#405).
                val type = classes.getValue(card.type)
                assertEquals("$name: ${card.type} isn't an enum", "Ljava/lang/Enum;", type.superclass)
                val constants = type.methods.single { it.name == "<clinit>" }
                for (constant in listOf(THREADS_MID_CARD, USER_SURVEY, "PYML_MIDCARD", "UNSET_OR_UNRECOGNIZED_ENUM_VALUE")) {
                    assertTrue("$name: the mid-card type enum doesn't name $constant", holdsString(constants, constant))
                }

                // Both getters are interface methods, and the item is a public reel item keeping the unit.
                for (owner in listOf(card.unit, card.model)) {
                    val flags = classes.getValue(owner).accessFlags
                    assertTrue("$name: $owner isn't a public interface",
                        AccessFlags.INTERFACE.isSet(flags) && AccessFlags.PUBLIC.isSet(flags))
                }
                val item = classes.getValue(card.item)
                assertTrue("$name: the item isn't public", AccessFlags.PUBLIC.isSet(item.accessFlags))
                assertTrue("$name: the item answers no story", item.methods.any {
                    it.parameterTypes.isEmpty() && it.returnType == "Lcom/facebook/graphql/model/GraphQLStory;"
                })
                assertEquals("$name: the item's unit field", card.unit,
                    item.instanceFields.single { it.name == card.unitField }.type)

                // Facebook's Reels code picks the item out of a page and reads its type the same way.
                val typeGetter = "${card.model}->${card.typeGetter}()${card.type}"
                var readers = 0
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { it.toString() == typeGetter }) return@forEach
                    for (classDef in dex.classes) readers += classDef.methods.count { readsTheType(it, card.item, typeGetter) }
                }
                assertTrue("$name: no Facebook method picks out ${card.item} and reads its type", readers >= 1)

                // The stub assembles with these names and uses its parameter register only.
                val context = PatchContexts.of(classes.values + ExtensionDex.classDef(REEL_MID_CARDS))
                with(context) { fillMidCardTypeStub(card) }
                val stub = with(context) { mutableClassDefBy(REEL_MID_CARDS) }.methods
                    .single { it.name == MID_CARD_TYPE_STUB && AccessFlags.STATIC.isSet(it.accessFlags) }
                val self = stub.implementation!!.registerCount - 1
                val body = stub.implementation!!.instructions.toList()
                assertEquals(
                    "$name: the stub's reads",
                    listOf(
                        Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE,
                        Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT,
                        Opcode.RETURN_OBJECT,
                    ),
                    body.take(9).map { it.opcode },
                )
                assertEquals(card.item, ((body[0] as ReferenceInstruction).reference as TypeReference).type)
                val field = (body[1] as ReferenceInstruction).reference as FieldReference
                assertEquals("${card.item}->${card.unitField}", "${field.definingClass}->${field.name}")
                assertEquals("${card.unit}->${card.modelGetter}()${card.model}",
                    ((body[3] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals(typeGetter, ((body[6] as ReferenceInstruction).reference as MethodReference).toString())
                val registers = listOf(
                    (body[0] as OneRegisterInstruction).registerA,
                    (body[1] as TwoRegisterInstruction).registerA, (body[1] as TwoRegisterInstruction).registerB,
                    (body[2] as OneRegisterInstruction).registerA,
                    (body[3] as FiveRegisterInstruction).registerC,
                    (body[4] as OneRegisterInstruction).registerA,
                    (body[5] as OneRegisterInstruction).registerA,
                    (body[6] as FiveRegisterInstruction).registerC,
                    (body[7] as OneRegisterInstruction).registerA,
                    (body[8] as OneRegisterInstruction).registerA,
                )
                assertEquals("$name: the stub's registers", List(registers.size) { self }, registers)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
