/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.downloads.reel.SIDEBAR
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.reels.gesture.COMMENT_TAP
import app.morphe.patches.facebook.reels.gesture.isCommentClick
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Comment button on a reel with comments turned off (#299): each declared build's button builder
 * and shared sidebar factory read can_viewer_comment and a second key a server flag swaps in, and
 * every such read hands its answer to the extension before Facebook branches on it.
 */
class ReelCommentButtonFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    private fun Method.loads(string: String) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    @Test
    fun `each declared build's two sidebar builders hand every comment check to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val buttons = FixtureDex.classesHolding(bundle, COMMENT_TAP).distinctBy { it.type }
                    .filter { owner -> owner.methods.count { isCommentClick(it, owner) } == 1 }
                assertEquals("$name: Comment button classes", 1, buttons.size)
                val button = buttons.single()
                // The builder is one that creates the button or, on 585, an instance method of the button's own class.
                val builders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.contains(button.type) }) { method ->
                    buildsCommentButton(method, button.type)
                }
                assertEquals("$name: methods building the Comment button", 1, builders.size)
                val builder = builders.single()
                val factories = FixtureDex.classesHolding(bundle, WHATSAPP_BUTTON_ID)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                    .flatMap { owner -> owner.methods.filter {
                        it.loads(WHATSAPP_BUTTON_ID) && it.loads(SIDEBAR) && it.loads(SHORTS_SIDEBAR)
                    }.map { owner to it } }
                assertEquals("$name: shared sidebar factories", 1, factories.size)
                val (factoryClass, factory) = factories.single()

                val alternate = alternateCommentKey(builder)
                assertNotNull("$name: the builder reads no second comment key", alternate)
                assertTrue("$name: the second key is can_viewer_comment", alternate != CAN_VIEWER_COMMENT)
                // 584 read the two keys in different branches of the factory; 585 reads them into one register as the builder does.
                alternateCommentKey(factory)?.let { assertEquals("$name: the factory's second key matches the builder's", alternate, it) }

                val owners = FixtureDex.classes(bundle, setOf(builder.definingClass))
                val classes = (owners.values + button + factoryClass).distinctBy { it.type }
                assertThrows("$name: no factory must refuse the patch", PatchException::class.java) {
                    PatchContexts.of(classes.filter { it.type != factoryClass.type }).showReelCommentButton()
                }
                val context = PatchContexts.of(classes)
                context.showReelCommentButton()
                var hooks = 0
                for (original in listOf(builder, factory)) {
                    val reads = commentGateReads(original, alternate!!)
                    assertTrue("$name: ${original.definingClass} reads can_viewer_comment", reads.any { CAN_VIEWER_COMMENT in it.keys })
                    assertTrue("$name: ${original.definingClass} reads the second key", reads.any { alternate in it.keys })
                    val patched = context.mutableClassDefBy(original.definingClass).methods.single {
                        it.name == original.name &&
                            it.parameterTypes.map(CharSequence::toString) == original.parameterTypes.map(CharSequence::toString)
                    }.code()
                    val calls = patched.indices.filter {
                        ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SHOW_COMMENT_BUTTON
                    }
                    assertEquals("$name: one hook per comment read in ${original.definingClass}", reads.size, calls.size)
                    for (at in calls) {
                        val result = (patched[at - 1] as OneRegisterInstruction).registerA
                        assertEquals("$name: the hook follows a move-result", Opcode.MOVE_RESULT, patched[at - 1].opcode)
                        assertEquals("$name: the hook passes the answer", result, (patched[at] as RegisterRangeInstruction).startRegister)
                        assertEquals("$name: the hook passes one register", 1, (patched[at] as RegisterRangeInstruction).registerCount)
                        assertEquals("$name: the extension's answer replaces Facebook's", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                        assertEquals("$name: into the same register", result, (patched[at + 1] as OneRegisterInstruction).registerA)
                    }
                    assertEquals("$name: two executable instructions per hook in ${original.definingClass}",
                        original.code().count { it.opcode != Opcode.NOP } + 2 * reads.size, patched.count { it.opcode != Opcode.NOP })
                    hooks += calls.size
                }
                assertTrue("$name: fewer than four comment checks hooked", hooks >= 4)
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hook`() {
        val type = SHOW_COMMENT_BUTTON.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "showsCommentButton" }
        assertNotNull("the extension has no ReelDeclutter.showsCommentButton", hook)
        assertEquals("showsCommentButton's shape", SHOW_COMMENT_BUTTON,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("showsCommentButton isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
