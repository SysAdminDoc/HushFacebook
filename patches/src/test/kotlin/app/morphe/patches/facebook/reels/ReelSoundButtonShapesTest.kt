/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Hide the speaker button on reels' matchers (#190) that need no Facebook build: what
 * counts as the video controls' check in front of the speaker and as the footer's build of it, and
 * what each refuses.
 */
class ReelSoundButtonShapesTest {
    /**
     * A viewer method: a call answering [returns], its move-result into v0, an if-eqz on v0 that jumps
     * past the [skipped] string loads, then the [after] loads and a return.
     */
    private fun viewer(
        skipped: List<String>,
        after: List<String> = emptyList(),
        call: Opcode = Opcode.INVOKE_STATIC,
        returns: String = "Z",
    ): Method {
        val check = ImmutableMethodReference("Lfixture/Viewer;", "A0Z", emptyList(), returns)
        val loads = { strings: List<String> -> strings.map { ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(it)) } }
        val code = listOf<Instruction>(
            if (call == Opcode.INVOKE_STATIC) ImmutableInstruction35c(call, 0, 0, 0, 0, 0, 0, check)
            else ImmutableInstruction35c(call, 1, 1, 0, 0, 0, 0, check),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            // The if-eqz is two code units and each string load two more.
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 2 + 2 * skipped.size),
        ) + loads(skipped) + loads(after) + ImmutableInstruction10x(Opcode.RETURN_VOID)
        return ImmutableMethod(
            "Lfixture/Viewer;", "A1C", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(2, code, null, null),
        )
    }

    @Test
    fun `a static check whose false skips only the speaker's key is the gate`() {
        val gate = soundButtonGates(viewer(listOf("android.widget.Button", SOUND_BUTTON_KEY))).single()
        assertEquals(SoundButtonGate(call = 0, branch = 2, answer = 0, target = 5), gate)
    }

    @Test
    fun `a check that skips another component's transition key with the speaker is refused`() {
        // Hooking it would take the play button (or the whole column) away with the speaker.
        assertTrue(soundButtonGates(viewer(listOf("play_component_transition_key", SOUND_BUTTON_KEY))).isEmpty())
        assertTrue(soundButtonGates(viewer(listOf(SOUND_BUTTON_KEY, "wnb_info_chip_transition_key"))).isEmpty())
    }

    @Test
    fun `a check that lands before the key, or isn't a static Z answer, is refused`() {
        // The branch skips something else and the key comes after where it lands.
        assertTrue(soundButtonGates(viewer(listOf("android.widget.Button"), after = listOf(SOUND_BUTTON_KEY))).isEmpty())
        assertTrue(soundButtonGates(viewer(listOf(SOUND_BUTTON_KEY), call = Opcode.INVOKE_VIRTUAL)).isEmpty())
        assertTrue(soundButtonGates(viewer(listOf(SOUND_BUTTON_KEY), returns = "I")).isEmpty())
        // A method that never loads the key has no gate for it.
        assertTrue(soundButtonGates(viewer(listOf("android.widget.Button"))).isEmpty())
    }

    /**
     * A footer piece's render: a call to Lfixture/Builder;->A00 answering [returns], its result moved
     * into v0, optionally a string load, then an if-eqz on [checked] over a nop, and a return.
     */
    private fun piece(
        call: Opcode = Opcode.INVOKE_STATIC,
        returns: String = "Lfixture/Row;",
        checked: Int = 0,
        between: Boolean = false,
    ): Method {
        val build = ImmutableMethodReference("Lfixture/Builder;", "A00", emptyList(), returns)
        val code = listOfNotNull<Instruction>(
            if (call == Opcode.INVOKE_STATIC) ImmutableInstruction35c(call, 0, 0, 0, 0, 0, 0, build)
            else ImmutableInstruction35c(call, 1, 1, 0, 0, 0, 0, build),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            if (between) ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("android.widget.Button")) else null,
            // Two code units for the if-eqz and one for the nop: it lands on the return.
            ImmutableInstruction21t(Opcode.IF_EQZ, checked, 3),
            ImmutableInstruction10x(Opcode.NOP),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        return ImmutableMethod(
            "Lfixture/Piece;", "render", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(2, code, null, null),
        )
    }

    private val anyBuilder = { _: MethodReference -> true }

    @Test
    fun `a static builder's answer moved and null-checked straight after is the footer's speaker`() {
        assertEquals(listOf(SpeakerBuild(call = 0, register = 0, type = "Lfixture/Row;")), speakerBuilds(piece(), anyBuilder))
        // The builder's class decides: one that doesn't news up the toggle isn't the speaker.
        assertTrue(speakerBuilds(piece()) { it.definingClass != "Lfixture/Builder;" }.isEmpty())
    }

    @Test
    fun `a call that isn't static, answers no object, or isn't null-checked straight after is refused`() {
        assertTrue(speakerBuilds(piece(call = Opcode.INVOKE_VIRTUAL), anyBuilder).isEmpty())
        assertTrue(speakerBuilds(piece(returns = "Z"), anyBuilder).isEmpty())
        // The check reads another register, or something comes between the move and the check.
        assertTrue(speakerBuilds(piece(checked = 1), anyBuilder).isEmpty())
        assertTrue(speakerBuilds(piece(between = true), anyBuilder).isEmpty())
    }

    @Test
    fun `the video controls' builder may be an instance method called virtually`() {
        val virtual = piece(call = Opcode.INVOKE_VIRTUAL)
        assertEquals(listOf(SpeakerBuild(call = 0, register = 0, type = "Lfixture/Row;")), controlSpeakerBuilds(virtual, anyBuilder))
        assertEquals(controlSpeakerBuilds(piece(), anyBuilder), speakerBuilds(piece(), anyBuilder))
        // The other shapes stay refused: no object answer, another register, something between.
        assertTrue(controlSpeakerBuilds(piece(call = Opcode.INVOKE_VIRTUAL, returns = "Z"), anyBuilder).isEmpty())
        assertTrue(controlSpeakerBuilds(piece(call = Opcode.INVOKE_VIRTUAL, checked = 1), anyBuilder).isEmpty())
        assertTrue(controlSpeakerBuilds(piece(call = Opcode.INVOKE_VIRTUAL, between = true), anyBuilder).isEmpty())
        assertTrue(controlSpeakerBuilds(virtual) { false }.isEmpty())
    }

    /** A builder A01 answering a Row: it may load [key] and news up [made], then returns the new object. */
    private fun builder(key: String?, made: String): Method {
        val code = listOfNotNull<Instruction>(
            key?.let { ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(it)) },
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(made)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        return ImmutableMethod(
            "Lfixture/Builder;", "A01", emptyList(), "Lfixture/Row;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(2, code, null, null),
        )
    }

    @Test
    fun `a controls builder is the method that holds the key and news up the sound toggle`() {
        val call = ImmutableMethodReference("Lfixture/Builder;", "A01", emptyList(), "Lfixture/Row;")
        val toggles = setOf("Lfixture/Toggle;")
        assertTrue(buildsControlsSpeaker(builder(SOUND_BUTTON_KEY, "Lfixture/Toggle;"), call, toggles))
        // Without the key, without the toggle, or under another name it isn't the speaker's builder.
        assertTrue(!buildsControlsSpeaker(builder(null, "Lfixture/Toggle;"), call, toggles))
        assertTrue(!buildsControlsSpeaker(builder(SOUND_BUTTON_KEY, "Lfixture/Other;"), call, toggles))
        assertTrue(!buildsControlsSpeaker(builder(SOUND_BUTTON_KEY, "Lfixture/Toggle;"), call, emptySet()))
        val other = ImmutableMethodReference("Lfixture/Builder;", "A02", emptyList(), "Lfixture/Row;")
        assertTrue(!buildsControlsSpeaker(builder(SOUND_BUTTON_KEY, "Lfixture/Toggle;"), other, toggles))
    }

    /**
     * 587's footer piece: a virtual call to Lfixture/Builder;->A02 on v1, its answer moved into v0 and
     * handed by [adds] to Lfixture/Column;->A00 answering [answers], called on [registers] (the column,
     * then the answer) with one [takes] parameter per register after the first, then a return.
     */
    private fun handingPiece(
        adds: Opcode = Opcode.INVOKE_VIRTUAL,
        answers: String = "V",
        takes: String = "Lfixture/Row;",
        registers: List<Int> = listOf(1, 0),
    ): Method {
        val build = ImmutableMethodReference("Lfixture/Builder;", "A02", emptyList(), "Lfixture/Row;")
        val add = ImmutableMethodReference("Lfixture/Column;", "A00", List(registers.size - 1) { takes }, answers)
        val slots = registers + List(5 - registers.size) { 0 }
        val code = listOf<Instruction>(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, build),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction35c(adds, registers.size, slots[0], slots[1], slots[2], slots[3], slots[4], add),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        return ImmutableMethod(
            "Lfixture/Piece;", "render", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(2, code, null, null),
        )
    }

    @Test
    fun `587's footer hands the answer to the column's add only when the builder answers null itself`() {
        val found = listOf(SpeakerBuild(call = 0, register = 0, type = "Lfixture/Row;"))
        assertEquals(found, footerSpeakerBuilds(handingPiece(), anyBuilder) { true })
        assertEquals(found, footerSpeakerBuilds(handingPiece(adds = Opcode.INVOKE_INTERFACE), anyBuilder) { true })
        // It's the builder that is asked whether it answers null, never the add.
        assertEquals(found, footerSpeakerBuilds(handingPiece(), anyBuilder) { it.definingClass == "Lfixture/Builder;" })
        assertTrue(footerSpeakerBuilds(handingPiece(), anyBuilder) { it.definingClass == "Lfixture/Column;" }.isEmpty())
        // A builder that never answers null would get its first null from the hook: refused.
        assertTrue(footerSpeakerBuilds(handingPiece(), anyBuilder) { false }.isEmpty())
        assertTrue(footerSpeakerBuilds(handingPiece(), { false }) { true }.isEmpty())
        // The null-checked shapes before 587 still count, with or without the builder's own null.
        assertEquals(found, footerSpeakerBuilds(piece(call = Opcode.INVOKE_VIRTUAL), anyBuilder) { false })
        assertEquals(found, footerSpeakerBuilds(piece(), anyBuilder) { false })
        // The controls and the static matcher never take a hand-on.
        assertTrue(controlSpeakerBuilds(handingPiece(), anyBuilder).isEmpty())
        assertTrue(speakerBuilds(handingPiece(), anyBuilder).isEmpty())
    }

    @Test
    fun `a hand-on that answers something, takes more or other, or isn't on another object is refused`() {
        val answersNull = { _: MethodReference -> true }
        assertTrue(footerSpeakerBuilds(handingPiece(answers = "Z"), anyBuilder, answersNull).isEmpty())
        assertTrue(footerSpeakerBuilds(handingPiece(takes = "I"), anyBuilder, answersNull).isEmpty())
        assertTrue(footerSpeakerBuilds(handingPiece(registers = listOf(1, 0, 0)), anyBuilder, answersNull).isEmpty())
        // The answer has to be the argument, on some other object.
        assertTrue(footerSpeakerBuilds(handingPiece(registers = listOf(0, 1)), anyBuilder, answersNull).isEmpty())
        assertTrue(footerSpeakerBuilds(handingPiece(registers = listOf(0, 0)), anyBuilder, answersNull).isEmpty())
        assertTrue(footerSpeakerBuilds(handingPiece(adds = Opcode.INVOKE_STATIC), anyBuilder, answersNull).isEmpty())
    }

    /** A builder A02 that puts [literal] in v[literalIn] with const/4, maybe a nop, then returns v[returned]. */
    private fun nullBuilder(literal: Int = 0, literalIn: Int = 0, returned: Int = 0, between: Boolean = false): Method {
        val code = listOfNotNull<Instruction>(
            ImmutableInstruction11n(Opcode.CONST_4, literalIn, literal),
            if (between) ImmutableInstruction10x(Opcode.NOP) else null,
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, returned),
        )
        return ImmutableMethod(
            "Lfixture/Builder;", "A02", emptyList(), "Lfixture/Row;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(2, code, null, null),
        )
    }

    @Test
    fun `a builder answers null itself only with a const 0 moved straight into its return`() {
        assertTrue(builderAnswersNull(nullBuilder()))
        assertTrue(builderAnswersNull(nullBuilder(literalIn = 1, returned = 1)))
        // Another constant, another register returned, or something between isn't a null answer.
        assertTrue(!builderAnswersNull(nullBuilder(literal = 1)))
        assertTrue(!builderAnswersNull(nullBuilder(returned = 1)))
        assertTrue(!builderAnswersNull(nullBuilder(between = true)))
        // A builder that only returns what it news up never answers null.
        assertTrue(!builderAnswersNull(builder(SOUND_BUTTON_KEY, "Lfixture/Toggle;")))
    }

    /**
     * A footer builder: an if-nez on v0 over the toggle's new-instance, landing on [landsOn], and a
     * return of the toggle in front of it. Two code units each for the if-nez and the new-instance and
     * one for the return, so the branch's offset of 5 lands just past them. Landing on a const/4, it
     * puts [literal] in v0 and returns v0 straight after, as 587's builder does.
     */
    private fun footerBuilder(check: Opcode = Opcode.IF_NEZ, landsOn: Opcode = Opcode.RETURN_OBJECT, literal: Int = 0): Method {
        val landing = when (landsOn) {
            Opcode.RETURN_OBJECT -> listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
            Opcode.CONST_4 -> listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, literal), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
            else -> listOf(ImmutableInstruction10x(landsOn))
        }
        val code = listOf<Instruction>(
            ImmutableInstruction21t(check, 0, 5),
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference("Lfixture/Toggle;")),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
        ) + landing
        return ImmutableMethod(
            "Lfixture/Builder;", "A00", emptyList(), "Lfixture/Row;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(2, code, null, null),
        )
    }

    @Test
    fun `the footer's check is the last if-nez in front of the toggle, landing on a return`() {
        val toggles = setOf("Lfixture/Toggle;")
        assertEquals(0, footerSpeakerCheck(footerBuilder(), toggles))
        // 587 lands on a const/4 of 0 moved straight into the return, which answers null the same way.
        assertEquals(0, footerSpeakerCheck(footerBuilder(landsOn = Opcode.CONST_4), toggles))
        // An if-eqz, a branch that lands anywhere but a return or a null one, or no toggle in the method isn't it.
        assertEquals(null, footerSpeakerCheck(footerBuilder(check = Opcode.IF_EQZ), toggles))
        assertEquals(null, footerSpeakerCheck(footerBuilder(landsOn = Opcode.NOP), toggles))
        assertEquals(null, footerSpeakerCheck(footerBuilder(landsOn = Opcode.CONST_4, literal = 1), toggles))
        assertEquals(null, footerSpeakerCheck(footerBuilder(), emptySet()))
    }

    /** A side column's render: asks a config interface, then returns at index 2 and has one more instruction, and maybe calls [other] first. */
    private fun sideRender(other: ImmutableMethodReference? = null): Method {
        val ask = ImmutableMethodReference("Lfixture/Config;", "BUh", emptyList(), "Z")
        val code = listOf<Instruction>(
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1, 0, 0, 0, 0, other ?: ask),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
            ImmutableInstruction10x(Opcode.NOP),
        )
        return ImmutableMethod(
            "Lfixture/Side;", "A17", emptyList(), "Lfixture/Row;", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(2, code, null, null),
        )
    }

    @Test
    fun `the side column's early exit is the config boolean in front of its first return`() {
        val config = listOf("Lfixture/Config;")
        assertEquals("BUh", earlyExitAsk(sideRender(), 3, config)?.name)
        // The return comes after the assembly, so nothing leaves early.
        assertEquals(null, earlyExitAsk(sideRender(), 1, config))
        // Something leaves early on an answer the corner doesn't hold, or that isn't a yes or no.
        assertThrows(PatchException::class.java) { earlyExitAsk(sideRender(), 3, listOf("Lfixture/Other;")) }
        assertThrows(PatchException::class.java) {
            earlyExitAsk(sideRender(ImmutableMethodReference("Lfixture/Config;", "A00", emptyList(), "I")), 3, config)
        }
    }

    @Test
    fun `the reel is the one type the speaker's builder, the side column and its assembly share`() {
        val session = "Lcom/facebook/auth/usersession/FbUserSession;"
        val side = { fields: List<String> ->
            ImmutableClassDef(
                "Lfixture/Side;", 0, "Ljava/lang/Object;", null, null, null,
                fields.mapIndexed { index, type -> ImmutableField("Lfixture/Side;", "A0$index", type, 0, null, null, null) }, emptyList(),
            )
        }
        val call = { parameters: List<String> -> ImmutableMethodReference("Lfixture/Any;", "A00", parameters, "V") }
        val builder = call(listOf(session, "Lfixture/Item;", "Lfixture/Context;"))
        val assembly = call(listOf(session, "Lfixture/Item;", "Lfixture/Context;"))
        assertEquals("Lfixture/Item;", reelItemType(builder, side(listOf(session, "Lfixture/Item;")), assembly))
        // The session isn't a reel, and two candidates pick neither.
        assertEquals(null, reelItemType(builder, side(listOf(session)), assembly))
        assertEquals(null, reelItemType(builder, side(listOf("Lfixture/Item;", "Lfixture/Context;")), assembly))
    }
}
