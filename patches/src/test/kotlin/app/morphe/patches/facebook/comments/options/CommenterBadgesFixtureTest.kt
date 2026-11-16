/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide badges like Top fan on comments on every Facebook build the bundle declares (#485). Each
 * socket that draws a badge by a commenter's name is found by the name it keeps, and the badge's
 * number is read from Facebook's own code by the badge plugin's kept class: the socket reads a
 * field of that class right where one of its branches on the plugin number lands, the socket hands
 * the same number to its check, and a check that opens on a switch sends that number to an arm
 * asking the plugin's class. Then the hook: first in the check, the number compared with the
 * badge's, the extension asked only on a match, a yes answering no, and a no landing on Facebook's
 * first instruction with nothing of Facebook's code moved. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class CommenterBadgesFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun holders(bundle: File, string: String) =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun List<Instruction>.opcodes() =
        map { if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode }

    private fun addresses(code: List<Instruction>) = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }

    /** Each switch key in [method] on [register] and the index its arm starts at. */
    private fun switchTargets(method: Method, register: Int): List<Pair<Int, Int>> {
        val code = method.implementation!!.instructions.toList()
        val addresses = addresses(code)
        return code.indices.filter {
            (code[it].opcode == Opcode.PACKED_SWITCH || code[it].opcode == Opcode.SPARSE_SWITCH) &&
                (code[it] as OneRegisterInstruction).registerA == register
        }.flatMap { at ->
            val payload = code[addresses.indexOf(addresses[at] + (code[at] as OffsetInstruction).codeOffset)] as SwitchPayload
            payload.switchElements.map { it.key to addresses.indexOf(addresses[at] + it.offset) }
        }
    }

    private fun lastRegister(call: Instruction): Int = when (call) {
        is RegisterRangeInstruction -> call.startRegister + call.registerCount - 1
        is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)[call.registerCount - 1]
        else -> error("not an invoke: $call")
    }

    private fun badge(bundle: File, socket: String, plugin: String) {
        val name = "${bundle.name}: $socket"
        val pluginClass = "L" + plugin.replace('.', '/') + ";"

        // Facebook's own code: the socket, the arm reading the badge plugin's class, and the number landing there.
        val renders = holders(bundle, socket).flatMap { methodsHolding(it, socket) }.filter { holdsString(it, plugin) }
        assertEquals("$name: one method names the socket and the badge", 1, renders.size)
        val render = renders.single()
        val code = render.implementation!!.instructions.toList()
        val arm = code.indices.single { index ->
            code[index].opcode == Opcode.SGET_OBJECT && (code[index].reference() as FieldReference).definingClass == pluginClass
        }
        assertEquals("$name: the arm names the badge right after reading its class", plugin,
            (code[arm + 1].reference() as StringReference).string)
        val checkCalls = code.filter { instruction ->
            (instruction.reference() as? MethodReference)?.let {
                it.definingClass == render.definingClass && it.returnType == "Z" && it.parameterTypes.lastOrNull()?.toString() == "I"
            } == true
        }
        assertTrue("$name: the socket asks no check", checkCalls.isNotEmpty())
        val register = checkCalls.map(::lastRegister).distinct().single()
        val addresses = addresses(code)
        val fromSwitch = switchTargets(render, register).filter { it.second == arm }.map { it.first }
        val fromZero = code.indices.filter { index ->
            code[index].opcode == Opcode.IF_EQZ && (code[index] as OneRegisterInstruction).registerA == register &&
                addresses.indexOf(addresses[index] + (code[index] as OffsetInstruction).codeOffset) == arm
        }.map { 0 }
        val expected = (fromSwitch + fromZero).distinct().single()
        val checkReference = checkCalls.map { it.reference() as MethodReference }.distinctBy { it.descriptor() }.single()
        val owner = FixtureDex.classes(bundle, setOf(render.definingClass)).values.single()
        val stock = owner.methods.single { it.descriptor() == checkReference.descriptor() }
        assertTrue("$name: the check isn't static", AccessFlags.STATIC.isSet(stock.accessFlags))
        val stockCode = stock.implementation!!.instructions.toList()
        val last = stock.implementation!!.registerCount - 1
        val opening = stockCode.first() as OneRegisterInstruction
        assertEquals("$name: the check opens on its number", last, opening.registerA)
        if (opening.opcode == Opcode.PACKED_SWITCH || opening.opcode == Opcode.SPARSE_SWITCH) {
            val arms = switchTargets(stock, last)
            val start = arms.single { it.first == expected }.second
            val end = arms.map { it.second }.filter { it > start }.minOrNull() ?: stockCode.size
            assertTrue("$name: the check's arm for ${expected} doesn't ask $pluginClass", stockCode.subList(start, end).any {
                (it.reference() as? MethodReference)?.definingClass == pluginClass
            })
        } else {
            assertEquals("$name: an if-z opening leaves only number 0 apart", Opcode.IF_EQZ, opening.opcode)
            assertEquals("$name: the badge isn't number 0", 0, expected)
        }

        // The finder agrees, and the hook goes in first.
        val found = commenterBadgeCheck(holders(bundle, socket), socket, plugin)
        assertNotNull("$name: the badge's check wasn't found", found)
        assertEquals("$name: the check", stock.descriptor(), found!!.check.descriptor())
        assertEquals("$name: the badge's number", expected, found.number)

        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == stock.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.holdCommenterBadge(found.number)
        val patched = method.implementation!!.instructions.toList()
        val where = "$name: ${stock.descriptor()}"
        assertEquals("$where gains seven instructions", original.size + 7, patched.size)
        assertEquals("$where: compares the number first", Opcode.RSUB_INT_LIT8, patched[0].opcode)
        assertEquals("$where: into the free register", 0, (patched[0] as TwoRegisterInstruction).registerA)
        assertEquals("$where: from the plugin's number", last, (patched[0] as TwoRegisterInstruction).registerB)
        assertEquals("$where: with the badge's", expected, (patched[0] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: other plugins skip the hook", Opcode.IF_NEZ, patched[1].opcode)
        assertEquals("$where: onto Facebook's first instruction", 7, (patched[1] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$where: the badge asks the extension", HIDES_COMMENTER_BADGE, (patched[2].reference() as MethodReference).descriptor())
        assertEquals("$where: its answer", Opcode.MOVE_RESULT, patched[3].opcode)
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[4].opcode)
        assertEquals("$where: onto its first instruction", 7, (patched[4] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$where: a yes answers no", listOf(Opcode.CONST_4, Opcode.RETURN), patched.subList(5, 7).map { it.opcode })
        assertEquals("$where: false", 0, (patched[5] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: Facebook's code stays", original.opcodes(), patched.drop(7).opcodes())
    }

    @Test
    fun `each declared build can keep the badge after a commenter's name out`() = bundles { bundle ->
        badge(bundle, TITLE_SOCKET, IDENTITY_BADGE)
    }

    @Test
    fun `each declared build can keep the badge over a commenter's name out`() = bundles { bundle ->
        badge(bundle, META_TITLE_SOCKET, META_IDENTITY_BADGE)
    }

    @Test
    fun `the patch hooks both badges, each in a check of its own`() = bundles { bundle ->
        assertEquals("a badge socket is left out of the patch",
            listOf(TITLE_SOCKET to IDENTITY_BADGE, META_TITLE_SOCKET to META_IDENTITY_BADGE), COMMENTER_BADGES)
        val checks = COMMENTER_BADGES.map { (socket, plugin) ->
            commenterBadgeCheck(holders(bundle, socket), socket, plugin)?.check?.descriptor()
        }
        assertTrue("${bundle.name}: a badge's check wasn't found", checks.all { it != null })
        assertNotEquals("${bundle.name}: both badges lead to one check", checks[0], checks[1])
    }
}
