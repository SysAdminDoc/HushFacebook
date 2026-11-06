/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The block button on comments (#271) on every Facebook build the bundle declares. The thumbs-down
 * component is the class the dislike plugin's builder returns, its render holds the icon constant
 * and the label pair, its click is the one method loading the undoReactionThenDownvote string, and
 * the block helper is the one instance method loading the block sheet's query name. Nothing here
 * names an obfuscated class. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class CommentBlockFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun holders(bundle: File, string: String) =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

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

    private class Found(val sites: CommentBlockSites, val classes: List<ClassDef>)

    private fun find(bundle: File): Found {
        val plugin = FixtureDex.classes(bundle, setOf(COMMENT_DOWNVOTE)).values.singleOrNull()
        assertNotNull("${bundle.name}: nothing is $COMMENT_DOWNVOTE", plugin)
        val seen = mutableMapOf<String, ClassDef?>()
        val classOf = { type: String ->
            seen.getOrPut(type) { FixtureDex.classes(bundle, setOf(type)).values.singleOrNull() }
        }
        val clicks = holders(bundle, DOWNVOTE_CLICK).flatMap { owner -> owner.methods.filter { holdsString(it, DOWNVOTE_CLICK) } }
        val helpers = holders(bundle, BLOCK_SHEET_QUERY).flatMap { owner -> owner.methods.filter { holdsString(it, BLOCK_SHEET_QUERY) } }
        val failures = holders(bundle, BLOCK_FAILED).flatMap { owner -> owner.methods.filter { holdsString(it, BLOCK_FAILED) } }
        val sites = commentBlockSites(dislikeBuilder(plugin), clicks, helpers, failures, classOf)
        val owners = listOfNotNull(sites.render.definingClass, sites.click.definingClass, sites.failure?.definingClass)
            .distinct().mapNotNull(classOf)
        return Found(sites, owners)
    }

    @Test
    fun `each declared build's thumbs-down is found and hooked`() = bundles { bundle ->
        val name = bundle.name
        val found = find(bundle)
        val sites = found.sites
        val renderBefore = sites.render.code()
        val clickBefore = sites.click.code()
        val failure = sites.failure
        assertNotNull("$name: no single block sheet failure handler", failure)
        val failureBefore = failure!!.code()

        assertTrue("$name: the icon isn't a static load", renderBefore[sites.iconAt].opcode == Opcode.SGET_OBJECT)
        assertTrue("$name: the label call isn't a direct call", renderBefore[sites.labelAt].opcode == Opcode.INVOKE_DIRECT)
        assertTrue("$name: the icon and the free register differ", sites.iconRegister != sites.scratchRegister)

        val context = PatchContexts.of(found.classes)
        hookCommentBlock(sites) { type -> context.mutableClassDefBy(type) }

        val render = context.mutableClassDefBy(sites.render.definingClass).methods.single { it.descriptor() == sites.render.descriptor() }.code()
        assertEquals("$name: render gains six instructions", renderBefore.size + 6, render.size)
        val ask = render.indexOfFirst { it.reference() == "$CLASS->blockLabel(Ljava/lang/String;)Ljava/lang/String;" }
        assertTrue("$name: the label is never handed over", ask >= 0)
        assertEquals("$name: its answer goes back", Opcode.MOVE_RESULT_OBJECT, render[ask + 1].opcode)
        assertEquals("$name: the label call follows", Opcode.INVOKE_DIRECT, render[ask + 2].opcode)
        val icon = render.indexOfFirst { it.reference() == "$CLASS->blockIcon()Z" }
        assertTrue("$name: the icon is never asked", icon > 0)
        assertEquals("$name: the thumbs-down load stays first", Opcode.SGET_OBJECT, render[icon - 1].opcode)
        assertEquals(
            "$name: the block icon replaces it on a yes",
            listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.SGET_OBJECT),
            render.subList(icon + 1, icon + 4).map { it.opcode },
        )
        assertEquals("$name: the block icon", sites.blockIcon, render[icon + 3].reference())
        assertTrue("$name: Facebook's render stays in order",
            renderBefore.map { it.opcode } == render.filterIndexed { i, _ -> i !in ask until ask + 2 && i !in icon until icon + 4 }.map { it.opcode })

        val clickMethod = context.mutableClassDefBy(sites.click.definingClass).methods.single { it.descriptor() == sites.click.descriptor() }
        val click = clickMethod.code()
        val at = sites.clickAt
        // 585 has a static click of (lambda, argument), hooked first thing. 585's Android 9 build keeps
        // it inline as one arm of the lambda's invoke(Object) switch: the hook takes the arm's label
        // and ends on a nop the arm's own code follows.
        val inlined = at > 0
        assertEquals("$name: an inlined click is an arm, an outlined one starts at 0",
            inlined, !AccessFlags.STATIC.isSet(sites.click.accessFlags))
        val hookSize = if (inlined) 12 else 11
        fun kind(opcode: Opcode) = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode
        assertEquals("$name: the click gains eleven instructions besides nops",
            clickBefore.count { it.opcode != Opcode.NOP } + 11, click.count { it.opcode != Opcode.NOP })
        assertEquals(
            "$name: the four classes and both objects are loaded, then the extension asked",
            listOf(
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.CONST_CLASS, Opcode.CONST_CLASS,
                Opcode.CONST_CLASS, Opcode.CONST_CLASS, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT,
            ) + (if (inlined) listOf(Opcode.NOP) else emptyList()),
            click.subList(at, at + hookSize).map { it.opcode },
        )
        val registers = clickMethod.implementation!!.registerCount
        val self = registers - 2
        assertEquals("$name: the lambda and its argument are handed over",
            listOf(self, self + 1), (0..1).map { (click[at + it] as TwoRegisterInstruction).registerB })
        assertEquals("$name: the component", sites.component, click[at + 2].reference())
        assertEquals("$name: the helper", sites.helper, click[at + 3].reference())
        assertEquals("$name: the profile", sites.profile, click[at + 4].reference())
        assertEquals("$name: the source", sites.source, click[at + 5].reference())
        assertEquals("$name: the extension is asked", "$CLASS->tap(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)Z", click[at + 6].reference())
        assertEquals("$name: a yes returns Unit", sites.unit, click[at + 9].reference())
        assertEquals("$name: Facebook's click stays",
            clickBefore.map { kind(it.opcode) }.filter { it != Opcode.NOP },
            click.filterIndexed { i, _ -> i !in at until at + hookSize }.map { kind(it.opcode) }.filter { it != Opcode.NOP })
        if (inlined) {
            // The arm is the downvote's own: the string sits in it, and the switch now lands on the hook.
            val flow = ControlFlow.of(clickMethod)
            val switch = click.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
            assertTrue("$name: the switch lands on the hook", at in flow.normal[switch])
            assertEquals("$name: the switch alone lands there", listOf(switch),
                click.indices.filter { at in flow.normal[it] || at in flow.exceptional[it] })
            assertEquals("$name: a no goes on to the arm's own code", at + 11,
                flow.normal[at + 8].single { it != at + 9 })
            val load = click.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == DOWNVOTE_CLICK }
            val seen = mutableSetOf(at + 11)
            val pending = ArrayDeque(listOf(at + 11))
            while (pending.isNotEmpty()) {
                (flow.normal[pending.removeFirst()]).forEach { if (seen.add(it)) pending += it }
            }
            assertTrue("$name: the arm goes on to load the downvote string", load in seen)
        }

        val handler = context.mutableClassDefBy(failure.definingClass).methods.single { it.descriptor() == failure.descriptor() }.code()
        assertEquals("$name: the failure handler gains one call", failureBefore.size + 1, handler.size)
        assertEquals("$name: the throwable goes to the extension first", "$CLASS->sheetFailed(Ljava/lang/Throwable;)V", handler[0].reference())
        assertEquals("$name: Facebook's failure handler stays", failureBefore.map { it.opcode }, handler.drop(1).map { it.opcode })
    }

    @Test
    fun `the extension has the hooks`() {
        val methods = ExtensionDex.classDef(CLASS).methods.associateBy { it.name }
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)Z",
            methods["tap"]?.let { "(" + it.parameterTypes.joinToString("") + ")" + it.returnType })
        assertNotNull(methods["blockIcon"])
        assertNotNull(methods["blockLabel"])
        assertEquals("(Ljava/lang/Throwable;)V", methods["sheetFailed"]?.let { "(" + it.parameterTypes.joinToString("") + ")" + it.returnType })
    }

    private companion object {
        const val CLASS = "Lapp/morphe/extension/facebook/comments/CommentBlock;"
    }
}
