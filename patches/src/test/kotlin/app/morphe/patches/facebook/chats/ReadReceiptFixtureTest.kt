/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide read receipts on every declared build: one Mailbox mark-read, and after the hook it asks the
 * extension right after its future is made and hands that future back before anything is posted.
 *
 * Read from 581 (2026-10-06): the mark-read is `LX/5LX;->A0D`, its future comes from
 * `LX/45j;->A0Y` and the call is posted by `LX/45m;->A1P`. None of those names is used here.
 */
class ReadReceiptFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    @Test
    fun `the mark-read hands back its future before it posts, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, MAILBOX_MARK_READ).filter { it.methods.any(::isMailboxMarkRead) }
                assertEquals("$name: one class holds Mailbox's mark-read", 1, owners.size)
                val owner = ImmutableClassDef.of(owners.single())
                val read = owner.methods.single(::isMailboxMarkRead)
                val before = read.implementation!!.instructions.toList()
                val at = read.futureReadyIndex()
                val future = read.futureRegister()
                // 585 moves the future out of a call, 586 builds it with new-instance and its constructor.
                val ready = before[at - 1]
                assertTrue("$name: the future isn't in v$future right before the hook: $ready",
                    (ready.opcode == Opcode.MOVE_RESULT_OBJECT && (ready as OneRegisterInstruction).registerA == future) ||
                        (ready.opcode == Opcode.INVOKE_DIRECT && ready.called()?.substringAfter("->")?.startsWith("<init>(") == true &&
                            (ready as FiveRegisterInstruction).registerC == future))
                assertTrue("$name: the future is made before anything is posted",
                    before.take(at).none { it.opcode.name.startsWith("invoke") && it.called()?.contains("MailboxCallback") == true })
                assertTrue("$name: the call is posted after it",
                    before.drop(at).any { it.called()?.contains("Lcom/facebook/msys/mca/MailboxCallback;") == true })

                val context = PatchContexts.of(listOf(owner))
                with(context) { hideReadReceipts() }

                val after = context.mutableClassDefBy(owner.type).methods.single(::isMailboxMarkRead).implementation!!.instructions.toList()
                assertEquals("$name: asks after the future is made", HOLDS_CHAT_READ, after[at].called())
                assertEquals("$name: tests the answer", Opcode.IF_EQZ, after[at + 2].opcode)
                assertEquals("$name: and hands back the future", Opcode.RETURN_OBJECT, after[at + 3].opcode)
                assertEquals("$name: the one it made", future, (after[at + 3] as OneRegisterInstruction).registerA)
                assertEquals("$name: the rest is as it was", before.size + 4, after.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
