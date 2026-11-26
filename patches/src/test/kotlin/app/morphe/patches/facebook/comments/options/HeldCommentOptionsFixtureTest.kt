/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a held comment offers (#607) on every declared build: one enum of option kinds, one handler
 * adding the options through one helper, one reaction wrapper and one "nothing left" check before
 * the older comment sheet. After the patch the helper asks the extension about each kind first,
 * the wrapper goes through the extension once built, and the check asks the extension before the
 * sheet, with the flag-off paths still jumping past that hook. Read from 588 (2026-11-26): the
 * handler is `LX/0DoQ;->A1F`, the helper `LX/0yPQ;->A01`, the enum `LX/0uDW;`, the wrapper
 * `LX/0Msl;`. None of those names is used here.
 */
class HeldCommentOptionsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Instruction.call() = ((this as ReferenceInstruction).reference as MethodReference).smali()

    private fun Instruction.target() = (this as BuilderOffsetInstruction).target.location.index

    private fun ClassDef.method(like: Method) = methods.single {
        it.name == like.name && it.parameterTypes.map(CharSequence::toString) == like.parameterTypes.map(CharSequence::toString)
    }

    @Test
    fun `each declared build's held comment asks the extension about its options, reactions and empty glimpse`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val enums = FixtureDex.classesHolding(bundle, OPTION_REPORT_TO_ADMIN)
                val kinds = optionKinds(enums)
                assertEquals("$name: option kind enums", 1, kinds.size)
                val holders = FixtureDex.classesHolding(bundle, GLIMPSE_BUBBLE)
                val handler = glimpseHandler(holders, kinds)
                assertNotNull("$name: no one held comment handler", handler)
                val helperCall = optionHelper(handler!!, kinds)
                assertNotNull("$name: no one option helper", helperCall)
                val helperClass = FixtureDex.classes(bundle, setOf(helperCall!!.definingClass)).getValue(helperCall.definingClass)
                val helper = helperClass.methods.single {
                    it.name == helperCall.name &&
                        it.parameterTypes.map(CharSequence::toString) == helperCall.parameterTypes.map(CharSequence::toString)
                }
                val empty = emptyGlimpse(handler)
                assertNotNull("$name: no one check opening the older sheet for an empty glimpse", empty)
                val wrapper = reactionWrapper(handler)
                assertNotNull("$name: no one reaction wrapper", wrapper)
                assertEquals("$name: the empty check reads another register than the reactions", wrapper!!.register, empty!!.reactions)
                assertTrue("$name: the reactions are wrapped before the empty check", wrapper.init > empty.done)
                val stockHandler = handler.implementation!!.instructions.toList()
                val stockHelper = helper.implementation!!.instructions.toList()
                val handlerClass = holders.single { it.type == handler.definingClass }
                val classes = (holders + enums + helperClass).distinctBy { it.type }

                // Negative control: without the enum nothing is found, and neither method changes.
                val bare = PatchContexts.of((holders + helperClass).distinctBy { it.type })
                bare.holdHeldCommentParts("test")
                assertEquals("$name: the patch changed the handler without the option kinds", stockHandler.map { it.opcode },
                    bare.mutableClassDefBy(handlerClass.type).method(handler).implementation!!.instructions.map { it.opcode })
                assertEquals("$name: the patch changed the helper without the option kinds", stockHelper.map { it.opcode },
                    bare.mutableClassDefBy(helperClass.type).method(helper).implementation!!.instructions.map { it.opcode })

                val context = PatchContexts.of(classes)
                context.holdHeldCommentParts("test")

                val row = context.mutableClassDefBy(helperClass.type).method(helper).implementation!!.instructions.toList()
                assertEquals("$name: the option hook's instructions",
                    listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), row.take(4).map { it.opcode })
                assertEquals("$name: the option hook calls something else", DROPS_HELD_OPTION, row[0].call())
                val kindRegister = helper.implementation!!.registerCount - helper.parameterTypes.size +
                    helper.parameterTypes.indexOfFirst { it.toString() in kinds }
                assertEquals("$name: the option hook reads another register", kindRegister, (row[0] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the option hook's branch skips somewhere else", 4, row[2].target())
                assertEquals("$name: the rest of the helper is Facebook's", stockHelper.map { it.opcode }, row.drop(4).map { it.opcode })

                val code = context.mutableClassDefBy(handlerClass.type).method(handler).implementation!!.instructions.toList()
                assertEquals("$name: the handler grew by other than the two hooks", stockHandler.size + 6, code.size)
                val hook = empty.first
                assertEquals("$name: the empty hook's instructions",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ), code.subList(hook, hook + 3).map { it.opcode })
                assertEquals("$name: the empty hook calls something else", HELD_LEFT_EMPTY, code[hook].call())
                assertEquals("$name: the empty hook branches on another register",
                    (code[hook + 1] as OneRegisterInstruction).registerA, (code[hook + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: on a yes the empty hook lands elsewhere than the return after the sheet", empty.done + 3, code[hook + 2].target())
                assertEquals("$name: the sheet no longer follows the empty hook", stockHandler[hook].opcode, code[hook + 3].opcode)
                val jumps = code.filterIsInstance<BuilderOffsetInstruction>()
                assertTrue("$name: a jump lands on the empty hook", jumps.none { it.target.location.index == hook })
                assertTrue("$name: no flag-off path jumps to the sheet", jumps.any { it.target.location.index == hook + 3 })

                val after = wrapper.init + 1 + 3
                assertEquals("$name: the reactions hook's instructions",
                    listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST), code.subList(after, after + 3).map { it.opcode })
                assertEquals("$name: the reactions hook calls something else", HELD_REACTIONS, code[after].call())
                assertEquals("$name: the reactions hook reads another register", wrapper.register, (code[after] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the reactions hook writes another register", wrapper.register, (code[after + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the reactions hook casts to another type", wrapper.type,
                    ((code[after + 2] as ReferenceInstruction).reference as TypeReference).type)
                assertEquals("$name: the handler outside the hooks is Facebook's", stockHandler.map { it.opcode },
                    (code.take(hook) + code.subList(hook + 3, after) + code.drop(after + 3)).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        val extension = ExtensionDex.classDef("$EXTENSION_PACKAGE/comments/HeldComment;")
        for (hook in listOf(DROPS_HELD_OPTION, HELD_REACTIONS, HELD_LEFT_EMPTY)) {
            val method = extension.methods.singleOrNull {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
            }
            assertTrue("the extension has no static $hook", method != null && AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }
}
