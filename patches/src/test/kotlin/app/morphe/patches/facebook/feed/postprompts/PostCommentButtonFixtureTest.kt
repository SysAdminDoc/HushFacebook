/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.reels.CAN_VIEWER_COMMENT
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Show Comment on posts with comments off (#299) on every Facebook build the bundle declares (arm64
 * and armv7): one static footer check loads a field of CommentButtonFeedUFIFooterItemPlugin, picks
 * the item through a packed-switch, and for the Comment item asks a static check that reads
 * can_viewer_comment (0x35e7e981, read from 588's dex) from the post's feedback. The patch hands
 * that one answer to the extension. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class PostCommentButtonFixtureTest {
    @Test
    fun `each declared build's footer check hands its can-comment answer to the extension`() {
        assertEquals("can_viewer_comment's key", 0x35e7e981, CAN_VIEWER_COMMENT)
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val resolved = mutableMapOf<String, com.android.tools.smali.dexlib2.iface.ClassDef?>()
                val classOf = { type: String -> resolved.getOrPut(type) { FixtureDex.classes(bundle, setOf(type))[type] } }
                val candidates = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.contains(COMMENT_BUTTON_PLUGIN) }) { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && loadsCommentPlugin(method)
                }
                val checks = candidates.filter { canCommentCalls(it, classOf).isNotEmpty() }
                assertEquals("$name: footer checks asking about comments", 1, checks.size)
                val check = checks.single()
                val original = check.implementation!!.instructions.toList()
                assertTrue("$name: the footer check picks its item through a packed-switch",
                    original.any { it.opcode == Opcode.PACKED_SWITCH })
                val calls = canCommentCalls(check, classOf)
                assertEquals("$name: can-comment calls in the footer check", 1, calls.size)
                val called = (original[calls.single()] as ReferenceInstruction).reference as MethodReference
                val target = classOf(called.definingClass)
                assertNotNull("$name: ${called.definingClass} isn't in the build", target)
                val result = (original[calls.single() + 1] as OneRegisterInstruction).registerA

                // The one footer list builder: static, answering a List, calling the check, and taking the
                // class the check reads the post off.
                val field = storyField(check, calls.single())
                assertNotNull("$name: the footer check reads no post off an Object field", field)
                val checkName = referenceOf(check)
                val builders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.contains(check.definingClass) }) {
                    buildsFooterList(it, checkName)
                }
                assertEquals("$name: footer list builders calling $checkName", 1, builders.size)
                val builder = builders.single()
                val story = parameterNumber(builder, field!!.definingClass)
                assertNotNull("$name: the builder takes no one ${field.definingClass}", story)
                val builderCode = builder.implementation!!.instructions.toList()
                val returns = builderCode.filter { it.opcode == Opcode.RETURN_OBJECT }
                assertTrue("$name: the builder returns nothing", returns.isNotEmpty())

                // Its items are a GraphQL enum answering the server's name from toString, COMMENT and REACT
                // (the Like button) among them, which is all the extension goes by.
                val items = builderCode.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType }
                    .distinct().filter { it.startsWith("L") && classOf(it)?.superclass == "Ljava/lang/Enum;" }
                assertEquals("$name: enums the builder reads", 1, items.size)
                val item = classOf(items.single())!!
                val constants = item.methods.single { it.name == "<clinit>" }
                for (server in listOf("COMMENT", "REACT", "SHARE")) {
                    assertTrue("$name: ${item.type} names no $server", holdsString(constants, server))
                }
                val toString = item.methods.single { it.name == "toString" && it.parameterTypes.isEmpty() }
                    .implementation!!.instructions.map { it.opcode }
                assertEquals("$name: ${item.type}.toString answers a field", listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), toString)

                val owner = classOf(check.definingClass)!!
                assertThrows("$name: without the can-comment check's class the patch must refuse", PatchException::class.java) {
                    PatchContexts.of(listOf(owner)).showPostCommentButton()
                }
                val builderOwner = classOf(builder.definingClass)!!
                val context = PatchContexts.of(listOf(owner, target!!, builderOwner).distinctBy { it.type })
                context.showPostCommentButton()
                val patched = context.mutableClassDefBy(check.definingClass).methods.single {
                    it.name == check.name && it.parameterTypes.map(CharSequence::toString) == check.parameterTypes.map(CharSequence::toString)
                }.implementation!!.instructions.toList()
                val patchedBuilder = context.mutableClassDefBy(builder.definingClass).methods.single {
                    it.name == builder.name && it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                }.implementation!!.instructions.toList()
                fun calledAt(at: Int) = ((patchedBuilder[at] as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

                // First thing, the post off the builder's own parameter (its register is reused later),
                // asked whether you can comment the way the footer check asks, and the answer handed over.
                val widths = builder.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                val postRegister = builder.implementation!!.registerCount - widths + story!!
                assertEquals("$name: the builder starts on its post", Opcode.MOVE_OBJECT_FROM16, patchedBuilder[0].opcode)
                assertEquals("$name: the post parameter's register", postRegister, (patchedBuilder[0] as TwoRegisterInstruction).registerB)
                val entry = patchedBuilder.indices.single { calledAt(it) == FOOTER_POST }
                val before = patchedBuilder.take(entry).mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
                assertEquals("$name: the start reads the post field and asks the check",
                    listOf(field.toString(), called.parameterTypes.single().toString(), called.toString()), before)
                assertEquals("$name: then the builder's own code", builderCode.first().opcode, patchedBuilder[entry + 1].opcode)

                // Before each return, the returned list goes to the extension and its answer is returned.
                val listHooks = patchedBuilder.indices.filter { calledAt(it) == FOOTER_WITH_COMMENT }
                assertEquals("$name: a list hook before each return", returns.size, listHooks.size)
                for (hook in listHooks) {
                    assertEquals("$name: the list hook is a static call", Opcode.INVOKE_STATIC_RANGE, patchedBuilder[hook].opcode)
                    val list = (patchedBuilder[hook] as RegisterRangeInstruction).startRegister
                    assertEquals("$name: the list hook gets one register", 1, (patchedBuilder[hook] as RegisterRangeInstruction).registerCount)
                    assertEquals("$name: the extension's list", Opcode.MOVE_RESULT_OBJECT, patchedBuilder[hook + 1].opcode)
                    assertEquals("$name: into the list's register", list, (patchedBuilder[hook + 1] as OneRegisterInstruction).registerA)
                    assertEquals("$name: then returned", Opcode.RETURN_OBJECT, patchedBuilder[hook + 2].opcode)
                    assertEquals("$name: that register returned", list, (patchedBuilder[hook + 2] as OneRegisterInstruction).registerA)
                }
                assertEquals("$name: instructions added to the builder", builderCode.size + entry + 1 + 2 * returns.size, patchedBuilder.size)
                assertEquals("$name: two instructions added", original.size + 2, patched.size)
                val hook = patched.indices.single {
                    ((patched[it] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == SHOW_POST_COMMENT_BUTTON
                }
                assertEquals("$name: the hook follows the can-comment call's move-result", calls.single() + 2, hook)
                assertEquals("$name: the hook is a static call", Opcode.INVOKE_STATIC_RANGE, patched[hook].opcode)
                assertEquals("$name: the hook gets Facebook's answer", result, (patched[hook] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the hook gets one register", 1, (patched[hook] as RegisterRangeInstruction).registerCount)
                assertEquals("$name: the extension's answer", Opcode.MOVE_RESULT, patched[hook + 1].opcode)
                assertEquals("$name: lands where Facebook branches on it", result, (patched[hook + 1] as OneRegisterInstruction).registerA)
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", Fixtures.declaredAbis(), Fixtures.abisOf(abis))
    }

    @Test
    fun `the extension has the hooks`() {
        for (reference in listOf(SHOW_POST_COMMENT_BUTTON, FOOTER_POST, FOOTER_WITH_COMMENT)) {
            val name = reference.substringAfter("->").substringBefore("(")
            val hook = ExtensionDex.classDef(reference.substringBefore("->")).methods.singleOrNull { referenceOf(it) == reference }
            assertNotNull("the extension has no $reference", hook)
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(hook!!.accessFlags))
        }
    }
}
