/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.HOLDS
import app.morphe.patches.facebook.comments.summaries.POST_SOCKET
import app.morphe.patches.facebook.comments.summaries.POST_SUMMARY
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.comments.summaries.holdSummaries
import app.morphe.patches.facebook.comments.summaries.nameTable
import app.morphe.patches.facebook.comments.summaries.socketCheck
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Related posts list under a post (#231) on every Facebook build the bundle declares: its
 * plugin is named by the same name table as the Meta AI summary under posts, and asked about by
 * the same check of the socket under a post's buttons. The hook on that check asks the Related
 * posts extension first, and with Hide Meta AI comment summaries' hook in the same check both
 * guards stack, each a no going on to the next and Facebook's code left as it was. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class RelatedPostsFixtureTest {
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

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun holders(bundle: File, string: String) =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

    @Test
    fun `each declared build holds the Related posts list in the check the Meta AI summary shares`() = bundles { bundle ->
        val name = bundle.name
        val table = nameTable(holders(bundle, RELATED_POSTS), RELATED_POSTS)
        assertEquals("$name: the Meta AI summary under posts has another table",
            table.descriptor(), nameTable(holders(bundle, POST_SUMMARY), POST_SUMMARY).descriptor())
        val owner = FixtureDex.classes(bundle, setOf(table.definingClass)).values.single()
        val sockets = holders(bundle, POST_SOCKET).flatMap { methodsHolding(it, POST_SOCKET) }
        assertTrue("$name: nothing names \"$POST_SOCKET\"", sockets.isNotEmpty())
        val check = socketCheck(table, owner, sockets)
        val where = "$name: ${check.descriptor()}"

        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == check.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.holdSummaries(table)
        method.holdSummaries(table, HOLDS_RELATED_POSTS)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains seven instructions for each hook", original.size + 14, patched.size)
        for ((start, hook) in listOf(0 to HOLDS_RELATED_POSTS, 7 to HOLDS)) {
            assertEquals("$where: the plugin's name comes from the table", table.descriptor(), patched[start].reference())
            assertEquals("$where: $hook isn't asked", hook, patched[start + 2].reference())
            assertEquals("$where: a no from $hook doesn't go on", Opcode.IF_EQZ, patched[start + 4].opcode)
            assertEquals("$where: a no from $hook lands somewhere else", setOf(start + 5, start + 7),
                ControlFlow.of(method).normal[start + 4].toSet())
        }
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(14).map { it.opcode })
    }

    @Test
    fun `each declared build holds the list after a post's comments in the bottom socket's check`() = bundles { bundle ->
        val name = bundle.name
        val table = nameTable(holders(bundle, RELATED_AFTER_COMMENTS), RELATED_AFTER_COMMENTS)
        val owner = FixtureDex.classes(bundle, setOf(table.definingClass)).values.single()
        val sockets = holders(bundle, COMMENT_BOTTOM_SOCKET).flatMap { methodsHolding(it, COMMENT_BOTTOM_SOCKET) }
        assertTrue("$name: nothing names \"$COMMENT_BOTTOM_SOCKET\"", sockets.isNotEmpty())
        val check = socketCheck(table, owner, sockets)
        val where = "$name: ${check.descriptor()}"

        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == check.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.holdSummaries(table, HOLDS_RELATED_POSTS)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains seven instructions", original.size + 7, patched.size)
        assertEquals("$where: the plugin's name comes from the table", table.descriptor(), patched[0].reference())
        assertEquals("$where: the extension isn't asked", HOLDS_RELATED_POSTS, patched[2].reference())
        assertEquals("$where: a no from the extension lands somewhere else", setOf(5, 7),
            ControlFlow.of(method).normal[4].toSet())
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(7).map { it.opcode })
    }

    @Test
    fun `each declared build asks the extension first in the plugin that asks for the list`() = bundles { bundle ->
        val plugin = FixtureDex.classes(bundle, setOf(RELATED_REQUEST)).values.single()
        val decider = requestDecider(plugin)
        val where = "${bundle.name}: ${decider.descriptor()}"
        assertTrue("$where has no local register", decider.implementation!!.registerCount > 1)

        val context = PatchContexts.of(listOf(plugin))
        val method = context.mutableClassDefBy(plugin.type).methods.single { it.descriptor() == decider.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.answerNoWhenKept()
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension isn't asked", KEEPS_OUT_OF_REQUEST, patched[0].reference())
        assertEquals("$where: a yes doesn't answer no", Opcode.RETURN, patched[4].opcode)
        assertEquals("$where: a no from the extension lands somewhere else", setOf(3, 5),
            ControlFlow.of(method).normal[2].toSet())
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
    }
}
