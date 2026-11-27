/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.actionrow

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.upsells.IMAGINE_CTA_SOCKET
import app.morphe.patches.facebook.misc.upsells.ctaCheck
import app.morphe.patches.facebook.misc.upsells.ctaTable
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Send message under posts (#609) on every Facebook build the bundle declares: the post
 * call-to-action selector's table answers a case with the Messenger Send message plugin's name, its
 * check is found once, and the patch puts the extension's question first in that check, so a yes
 * answers no and Facebook's check runs as before otherwise. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SendMessageCtaFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build's selector check asks the extension about the Send message bar first`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val tableHolders = FixtureDex.classesHolding(bundle, SEND_MESSAGE_PLUGIN)
                val table = ctaTable(tableHolders, SEND_MESSAGE_PLUGIN, "Post action buttons")
                val tableCode = table.code()
                val named = tableCode.indexOfFirst { holds(it, SEND_MESSAGE_PLUGIN) }
                assertEquals("$name: the selector's table answers a case with the Send message plugin",
                    Opcode.RETURN_OBJECT, tableCode[named + 1].opcode)
                val socketHolders = FixtureDex.classesHolding(bundle, IMAGINE_CTA_SOCKET)
                val sockets = socketHolders.flatMap { methodsHolding(it, IMAGINE_CTA_SOCKET) }
                val calledClasses = FixtureDex.classes(bundle, sockets.flatMap { method ->
                    method.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.map { it.definingClass }
                }.toSet())
                val check = ctaCheck(table, sockets, "Post action buttons") { calledClasses[it] }

                val pool = (tableHolders + socketHolders + listOfNotNull(calledClasses[check.definingClass]))
                    .associateBy { it.type }.values
                val context = PatchContexts.of(pool)
                val found = context.findSendMessageCheck()
                assertEquals("$name: the patch's table", table.descriptor(), found.table.descriptor())
                assertEquals("$name: the patch's check", check.descriptor(), found.check.descriptor())
                context.hideSendMessage()

                val guarded = context.mutableClassDefBy(check.definingClass).methods.single {
                    it.descriptor() == check.descriptor()
                }.code()
                val where = "$name: ${check.definingClass}->${check.name}"
                assertEquals("$where gains seven instructions", check.code().size + 7, guarded.size)
                assertEquals("$where: the plugin's name comes from the table", Opcode.INVOKE_STATIC_RANGE, guarded[0].opcode)
                assertEquals("$where: the plugin's name comes from the table", table.descriptor(), called(guarded[0]))
                assertEquals("$where: with the check's own number", check.implementation!!.registerCount - 1,
                    (guarded[0] as RegisterRangeInstruction).startRegister)
                assertEquals("$where: the extension is asked", HIDES_SEND_MESSAGE, called(guarded[2]))
                assertEquals("$where: a yes answers no", listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                    guarded.subList(3, 7).map { it.opcode })
                assertEquals("$where: Facebook's check stays", check.code().map { it.opcode }, guarded.drop(7).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    private fun called(instruction: Instruction) = ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.descriptor()

    private fun holds(instruction: Instruction, string: String) =
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == string
}
