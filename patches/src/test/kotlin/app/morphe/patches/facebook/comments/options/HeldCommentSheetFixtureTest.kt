/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.downloads.photo.COMMENT_SHEET_SOCKET
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the older comment sheet offers a held comment (#607) on every declared build: one render,
 * one static naming its plugins and one checking them, one row builder and one flag deciding the
 * reaction row. After the patch the plugin check asks the extension about each plugin's name
 * first, the row builder asks about each icon first, and render asks before building the reaction
 * row, going where a false flag goes on a yes. Read from 588 (2026-11-26): render is
 * `LX/0gDG;->render`, the plugin name `A0E`, the check `A0F`, the builder `A0B`, the reaction
 * row's pick handler `LX/0brn;`. None of those names is used here.
 */
class HeldCommentSheetFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Method.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Instruction.call() = ((this as ReferenceInstruction).reference as MethodReference).smali()

    private fun Instruction.target() = (this as BuilderOffsetInstruction).target.location.index

    private fun ClassDef.method(like: Method) = methods.single { it.smali() == like.smali() }

    private fun Method.lastParameterRegister() = implementation!!.registerCount - 1

    @Test
    fun `each declared build's older comment sheet asks the extension about its plugins, rows and reactions`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, COMMENT_SHEET_SOCKET)
                val render = sheetRender(holders)
                assertNotNull("$name: no one comment sheet render", render)
                val owner = holders.single { it.type == render!!.definingClass }
                val pluginName = sheetPluginName(owner)
                assertNotNull("$name: no one static naming the sheet's plugins", pluginName)
                val check = sheetPluginCheck(render!!, owner)
                assertNotNull("$name: no one check on the sheet's plugins", check)
                val builderCall = sheetRowBuilder(render)
                assertNotNull("$name: no one row builder", builderCall)
                val builderClass = FixtureDex.classes(bundle, setOf(builderCall!!.definingClass)).getValue(builderCall.definingClass)
                val builder = builderClass.methods.single { it.smali() == builderCall.smali() }
                val pickers = FixtureDex.classesHolding(bundle, SHEET_REACTION_PICKED)
                val point = sheetReactions(render, pickers.map { it.type }.toSet())
                assertNotNull("$name: no one flag deciding the reaction row", point)
                val stockRender = render.implementation!!.instructions.toList()
                val stockCheck = check!!.implementation!!.instructions.toList()
                val stockBuilder = builder.implementation!!.instructions.toList()
                val classes = (holders + pickers + builderClass).distinctBy { it.type }

                // Negative control: without the reaction row's pick handler render stays Facebook's.
                val bare = PatchContexts.of((holders + builderClass).distinctBy { it.type })
                bare.holdHeldCommentSheet("test")
                assertEquals("$name: the patch changed render without the reaction row's pick handler", stockRender.map { it.opcode },
                    bare.mutableClassDefBy(owner.type).method(render).implementation!!.instructions.map { it.opcode })

                val context = PatchContexts.of(classes)
                context.holdHeldCommentSheet("test")

                val plugins = context.mutableClassDefBy(owner.type).method(check).implementation!!.instructions.toList()
                assertEquals("$name: the plugin hook's instructions",
                    listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT,
                        Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN), plugins.take(7).map { it.opcode })
                assertEquals("$name: the plugin hook names plugins through something else", pluginName!!.smali(), plugins[0].call())
                assertEquals("$name: the plugin hook reads another register", check.lastParameterRegister(),
                    (plugins[0] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the plugin hook calls something else", DROPS_SHEET_PLUGIN, plugins[2].call())
                assertEquals("$name: the plugin hook's branch skips somewhere else", 7, plugins[4].target())
                assertEquals("$name: the rest of the plugin check is Facebook's", stockCheck.map { it.opcode }, plugins.drop(7).map { it.opcode })

                val rows = context.mutableClassDefBy(builderClass.type).method(builder).implementation!!.instructions.toList()
                assertEquals("$name: the row hook's instructions",
                    listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                    rows.take(5).map { it.opcode })
                assertEquals("$name: the row hook calls something else", DROPS_SHEET_ROW, rows[0].call())
                assertEquals("$name: the row hook reads another register than the icon", builder.lastParameterRegister(),
                    (rows[0] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the row hook's branch skips somewhere else", 5, rows[2].target())
                assertEquals("$name: the rest of the row builder is Facebook's", stockBuilder.map { it.opcode }, rows.drop(5).map { it.opcode })

                val code = context.mutableClassDefBy(owner.type).method(render).implementation!!.instructions.toList()
                assertEquals("$name: render grew by other than the reaction hook", stockRender.size + 3, code.size)
                val hook = point!!.test + 1
                assertEquals("$name: the reaction hook's instructions",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ), code.subList(hook, hook + 3).map { it.opcode })
                assertEquals("$name: the reaction hook calls something else", DROPS_SHEET_REACTIONS, code[hook].call())
                assertEquals("$name: the reaction hook branches on another register",
                    (code[hook + 1] as OneRegisterInstruction).registerA, (code[hook + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: on a yes the reaction hook lands elsewhere than a false flag", code[point.test].target(), code[hook + 2].target())
                assertEquals("$name: a false flag lands elsewhere than before", point.skip + 3, code[point.test].target())
                assertTrue("$name: a jump lands on the reaction hook",
                    code.filterIsInstance<BuilderOffsetInstruction>().none { it.target.location.index == hook })
                assertEquals("$name: render outside the hook is Facebook's", stockRender.map { it.opcode },
                    (code.take(hook) + code.drop(hook + 3)).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        val extension = ExtensionDex.classDef("$EXTENSION_PACKAGE/comments/HeldComment;")
        for (hook in listOf(DROPS_SHEET_PLUGIN, DROPS_SHEET_ROW, DROPS_SHEET_REACTIONS)) {
            val method = extension.methods.singleOrNull {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
            }
            assertTrue("the extension has no static $hook", method != null && AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }
}
