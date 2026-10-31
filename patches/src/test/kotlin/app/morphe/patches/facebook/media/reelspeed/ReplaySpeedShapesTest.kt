/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.reelspeed

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The parts of Speed on finished live videos that need no Facebook build: where the gear menu
 * builder's engagement test is taken to be, where the hook goes and what it leaves, and the shapes
 * it refuses.
 */
class ReplaySpeedShapesTest {
    private val builderType = "Lfixture/GearMenu;"
    private val rowType = "Lfixture/SpeedRow;"
    private val params = "Lfixture/Params;"
    private val sponsored = ImmutableFieldReference(params, "A1w", "Z")

    private fun method(
        body: String,
        parameters: List<String> = listOf(params, "Lfixture/Supplier;"),
        returnType: String = "Ljava/lang/Object;",
        registers: Int = 8,
        name: String = "A00",
        owner: String = builderType,
    ) = MutableMethod(
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
            null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /**
     * The gear menu builder as 585 has it, cut down: isSponsored kept through a move, the engagement
     * state, the MobileConfig flag, then the flag's if-nez, the engagement if-eqz and the goto onto
     * the speed row, which casts its click handler and tests isSponsored.
     */
    private fun builder(
        sponsoredRead: String = "iget-boolean v1, p0, $params->A1w:Z",
        keep: String = "move v2, v1",
        beforeTest: String = "",
        flagTest: String = "if-nez v5, :row",
        jump: String = "goto/16 :row",
        none: String = "",
        cast: String = "check-cast v0, $rowType",
        rowTest: String = "if-nez v2, :done",
        afterRow: String = "const/4 v1, 0x0",
        owner: String = builderType,
    ) = method(
        """
            const/4 v0, 0x0
            $sponsoredRead
            $keep
            const-string v3, "$ENGAGEMENT_STATE"
            invoke-virtual { v3, v3 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v4
            invoke-static { }, Lfixture/Config;->A0Z()Z
            move-result v5
            $flagTest
            $beforeTest
            if-eqz v4, :none
            $jump
            :none
            $none
            const/4 v0, 0x0
            return-object v0
            :row
            invoke-interface { p1 }, Lfixture/Supplier;->get()Ljava/lang/Object;
            move-result-object v0
            $cast
            $rowTest
            $afterRow
            :done
            return-object v0
        """,
        owner = owner,
    )

    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun classOf(type: String, vararg methods: Method): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods.toList(),
    )

    /** The speed row's click handler, holding the tap log name the patch finds it by. */
    private fun speedRow(type: String = rowType) = classOf(
        type,
        method(
            """
                const-string v0, "$GEAR_SPEED_TAP"
                return-void
            """,
            parameters = emptyList(), returnType = "V", registers = 1, owner = type,
        ),
    )

    @Test
    fun `the engagement test is the if-eqz between the flag's if-nez and the goto onto the speed row`() {
        val site = replaySpeedSite(builder(), rowType, sponsored)
        assertEquals("the test", 9, site.test)
        assertEquals("the speed row", 13, site.speedRow)
        assertEquals("isSponsored", 2, site.sponsored)
        assertEquals("the engagement state", 4, site.engaged)
        assertEquals("the flag, free after the test", 5, site.scratch)

        // A short goto, and isSponsored tested where it was read.
        val plain = replaySpeedSite(builder(keep = "", rowTest = "if-nez v1, :done", jump = "goto :row"), rowType, sponsored)
        assertEquals("isSponsored with no move", 1, plain.sponsored)
        assertEquals("the test before a short goto", 8, plain.test)
    }

    @Test
    fun `the hook asks the extension in front of the engagement test, after Facebook's own yes`() {
        val original = builder()
        val before = original.body()
        val context = PatchContexts.of(listOf(classOf(builderType, original), speedRow()))
        context.offerReplaySpeed(sponsored)
        val patched = context.mutableClassDefBy(builderType).methods.single()
        val after = patched.body()

        assertEquals(listOf(Opcode.IF_NEZ, Opcode.IF_NEZ, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.IF_EQZ),
            after.subList(8, 14).map { it.opcode })
        assertEquals("the extension's question", OFFER_SPEED, (after[10] as ReferenceInstruction).reference.toString())
        val call = after[10] as RegisterRangeInstruction
        assertEquals("the register handed over", 2 to 1, call.startRegister to call.registerCount)
        assertEquals("the engagement state tested first", 4, (after[9] as OneRegisterInstruction).registerA)
        assertEquals("the answer taken in the free flag register", listOf(5, 5),
            listOf(11, 12).map { (after[it] as OneRegisterInstruction).registerA })

        val flow = ControlFlow.of(patched)
        val row = 13 + 4
        assertEquals("Facebook's flag still jumps to the row", setOf(row, 9), flow.normal[8].toSet())
        assertEquals("an engaged player goes to the row", setOf(row, 10), flow.normal[9].toSet())
        assertEquals("a yes goes to the row and a no to Facebook's test", setOf(row, 13), flow.normal[12].toSet())
        assertEquals("Facebook's test still leaves the row out", setOf(15, 14), flow.normal[13].toSet())
        assertEquals("Facebook's goto still goes to the row", listOf(row), flow.normal[14])
        assertEquals("the row is the supplier's call", Opcode.INVOKE_INTERFACE, after[row].opcode)
        assertEquals(before.map { it.opcode }, after.filterIndexed { index, _ -> index !in 9..12 }.map { it.opcode })
    }

    @Test
    fun `shapes that aren't the engagement test are refused and left alone`() {
        val refused = mapOf(
            "a row cast to another class" to builder(cast = "check-cast v0, Lfixture/Other;"),
            "no goto after the if-eqz" to builder(jump = "const/4 v0, 0x0\ngoto :row"),
            "the if-nez jumping elsewhere" to builder(flagTest = "if-nez v5, :none"),
            "something between the if-nez and the if-eqz" to builder(beforeTest = "const/4 v0, 0x0"),
            "a jump onto the engagement test" to builder(beforeTest = ":test", afterRow = "if-eqz v1, :test"),
            "the flag read again after the test" to builder(none = "invoke-static { v5 }, Lfixture/Config;->A0X(I)V"),
            "isSponsored from another field" to builder(sponsoredRead = "iget-boolean v1, p0, $params->A2U:Z"),
            "isSponsored set to a constant" to builder(keep = "const/4 v2, 0x0"),
            "isSponsored moved from a constant" to builder(sponsoredRead = "const/4 v1, 0x0"),
            "no isSponsored test on the row" to builder(rowTest = "if-eqz v2, :done"),
        )
        for ((shape, original) in refused) {
            val count = original.body().size
            assertThrows(shape, PatchException::class.java) { replaySpeedSite(original, rowType, sponsored) }
            val context = PatchContexts.of(listOf(classOf(builderType, original), speedRow()))
            assertThrows(shape, PatchException::class.java) { context.offerReplaySpeed(sponsored) }
            assertEquals("$shape changed", count, context.mutableClassDefBy(builderType).methods.single().body().size)
        }

        // No speed row class, or two builders.
        assertThrows(PatchException::class.java) { PatchContexts.of(listOf(classOf(builderType, builder()))).offerReplaySpeed(sponsored) }
        val twice = PatchContexts.of(listOf(
            classOf(builderType, builder()),
            classOf("Lfixture/OtherMenu;", builder(owner = "Lfixture/OtherMenu;")),
            speedRow(),
        ))
        assertThrows(PatchException::class.java) { twice.offerReplaySpeed(sponsored) }
    }
}
