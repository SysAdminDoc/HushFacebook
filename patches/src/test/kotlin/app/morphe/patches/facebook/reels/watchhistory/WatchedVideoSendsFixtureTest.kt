/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.watchhistory

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two senders #408 holds back on every Facebook build the bundle declares: one method sends
 * the watched-position mutation, one method logs every VOD/Reels view-time event, and the hook puts
 * its question in front of each without moving anything else. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class WatchedVideoSendsFixtureTest {
    /** The view-time events, each logged by a method that hands its name to the logger. */
    private val events = listOf("android_update_video_view_time", "vpv_event_created", "vpv_event_attach_to_query")

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    /** An instruction as text, without branch offsets: its opcode, registers, reference and any literal. */
    private fun render(instruction: Instruction): String = buildString {
        append(instruction.opcode).append(' ').append(instruction.namedRegisters())
        (instruction as? ReferenceInstruction)?.let { append(' ').append(it.reference) }
        (instruction as? NarrowLiteralInstruction)?.let { append(" #").append(it.narrowLiteral) }
        (instruction as? WideLiteralInstruction)?.let { append(" #").append(it.wideLiteral) }
    }

    private fun methods(bundle: File, literal: String, wanted: (Method) -> Boolean): List<Method> =
        FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.contains(literal) }, wanted)

    /** Puts the hook in [method] and checks the guard leads it and every original instruction follows unchanged. */
    private fun checkHook(name: String, method: Method, hold: String) {
        val code = method.implementation!!.instructions.toList()
        val patched = MutableMethod(method)
        patched.holdAtStart(hold)
        val after = patched.implementation!!.instructions.toList()
        assertEquals("$name: instructions added", code.size + 4, after.size)
        assertEquals("$name: the question", hold, after[0].call!!.let {
            it.definingClass + "->" + it.name + it.parameterTypes.joinToString("", "(", ")") + it.returnType
        })
        assertEquals("$name: the answer", Opcode.MOVE_RESULT, after[1].opcode)
        assertEquals("$name: the branch", Opcode.IF_EQZ, after[2].opcode)
        // if-eqz is two code units and return-void one, so a branch of 3 lands on the original first instruction.
        assertEquals("$name: where the branch lands", 3, (after[2] as OffsetInstruction).codeOffset)
        assertEquals("$name: the early return", Opcode.RETURN_VOID, after[3].opcode)
        for (index in code.indices) {
            assertEquals("$name: instruction $index changed", render(code[index]), render(after[index + 4]))
        }
    }

    @Test
    fun `each declared build has one watched-position sender and one view-time logger, and the hook leads both`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name

                // Every method naming the mutation is the sender the patch picks.
                val naming = methods(bundle, POSITION_MUTATION) { holdsString(it, POSITION_MUTATION) }
                val senders = naming.filter(::positionSend)
                assertEquals("$name: methods loading \"$POSITION_MUTATION\"", 1, naming.size)
                assertEquals("$name: watched-position senders", 1, senders.size)

                // One logger, and every view-time event goes through it.
                val loggers = methods(bundle, VIEW_TIME_POSITION, ::viewTimeLog)
                assertEquals("$name: view-time loggers", 1, loggers.size)
                val logger = loggers.single()
                for (event in events) {
                    val logging = methods(bundle, event) { holdsString(it, event) }
                    assertEquals("$name: methods loading \"$event\"", 1, logging.size)
                    val calls = logging.single().implementation!!.instructions.mapNotNull { it.call }
                    assertTrue("$name: \"$event\" doesn't go through the logger", calls.any {
                        it.definingClass == logger.definingClass && it.name == logger.name &&
                            it.parameterTypes.map(Any::toString) == logger.parameterTypes.map(Any::toString)
                    })
                }

                checkHook("$name position", senders.single(), HOLD_POSITION)
                checkHook("$name view time", logger, HOLD_VIEW_TIME)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * "Videos you've watched" is filled from the playback events the players log, and the extension
     * knows them by how their names start. Each declared build still loads both everyday forms.
     */
    @Test
    fun `each declared build logs its playback events under the names the extension holds`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                for (event in listOf("media_playback_compound", "media_playback_compound_fast")) {
                    assertTrue("${bundle.name}: nothing loads \"$event\"", methods(bundle, event) { holdsString(it, event) }.isNotEmpty())
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
