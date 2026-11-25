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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep the keyboard closed when comments open (#594) on every declared build: the params comments
 * open with name their keyboard flag in their toString, the builder's constructor writes it once,
 * and after the patch that write takes the extension's answer. Read from 588 (2026-11-25): the flag
 * is `A0x`, written at 108 of `<init>(LX/084R;)V` from v0. None of those names is used here.
 */
class CommentKeyboardFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    @Test
    fun `each declared build's comment params take the extension's pick of the keyboard`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val params = FixtureDex.classes(bundle, setOf(FEEDBACK_PARAMS))[FEEDBACK_PARAMS]
                assertNotNull("$name: no $FEEDBACK_PARAMS", params)
                params!!
                val field = keyboardField(params)
                assertNotNull("$name: the params' toString names no keyboard flag", field)
                field!!
                fun writes(field: FieldReference, method: com.android.tools.smali.dexlib2.iface.Method) =
                    method.implementation?.instructions?.toList().orEmpty().count { instruction ->
                        instruction.opcode == Opcode.IPUT_BOOLEAN &&
                            ((instruction as ReferenceInstruction).reference as FieldReference).name == field.name
                    }
                // What the comment's notes say: the Parcel constructor writes the flag too, and is left alone.
                val fromParcel = params.methods.filter { it.name == "<init>" && "Landroid/os/Parcel;" in parameterTypeNames(it) }
                assertEquals("$name: Parcel constructors writing the flag", 1, fromParcel.count { writes(field, it) == 1 })

                // Negative control: params whose toString is gone give no flag, and the patch leaves them as they were.
                val bare = ImmutableClassDef(
                    params.type, params.accessFlags, params.superclass, params.interfaces, params.sourceFile,
                    params.annotations, params.fields.toList(), params.methods.filter { it.name != "toString" },
                )
                assertNull("$name: a flag found with no toString", keyboardField(bare))
                val untouched = PatchContexts.of(listOf(bare))
                untouched.keepCommentKeyboardClosed("test")
                val write = keyboardWrite(params, field) { error("$name: $it") }!!
                val stock = write.init.implementation!!.instructions.toList()
                assertEquals("$name: the patch changed params it couldn't read", stock.map { it.opcode },
                    untouched.mutableClassDefBy(params.type).methods.single { it.name == "<init>" && parameterTypeNames(it) == parameterTypeNames(write.init) }
                        .implementation!!.instructions.map { it.opcode })

                val context = PatchContexts.of(listOf(params))
                context.keepCommentKeyboardClosed("test")
                val patched = context.mutableClassDefBy(params.type).methods
                    .single { it.name == "<init>" && parameterTypeNames(it) == parameterTypeNames(write.init) }
                    .implementation!!.instructions.toList()
                assertEquals("$name: two instructions added", stock.size + 2, patched.size)
                val call = patched[write.index]
                assertEquals("$name: the hook isn't where the flag was written", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                assertEquals("$name: the hook calls something else", KEYBOARD_ON_FIRST_LOAD,
                    ((call as ReferenceInstruction).reference as MethodReference).smali())
                assertEquals("$name: the hook gets another register", write.register, (call as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the hook gets more than the flag", 1, call.registerCount)
                assertEquals("$name: the answer isn't taken", Opcode.MOVE_RESULT, patched[write.index + 1].opcode)
                assertEquals("$name: the answer goes elsewhere", write.register, (patched[write.index + 1] as OneRegisterInstruction).registerA)
                val put = patched[write.index + 2]
                assertEquals("$name: the flag's write moved", Opcode.IPUT_BOOLEAN, put.opcode)
                assertEquals("$name: another field is written after the hook", field.name,
                    ((put as ReferenceInstruction).reference as FieldReference).name)
                val skipping = patched.withIndex().filter { (_, instruction) ->
                    (instruction as? BuilderOffsetInstruction)?.target?.location?.index in setOf(write.index + 1, write.index + 2)
                }
                assertTrue("$name: a branch goes past the hook: ${skipping.map { it.index }}", skipping.isEmpty())
                assertEquals("$name: the rest of the constructor is Facebook's", stock.map { it.opcode },
                    patched.filterIndexed { index, _ -> index != write.index && index != write.index + 1 }.map { it.opcode })
                // The Parcel constructor reads back what the hooked one wrote, so it stays as it was.
                for (init in fromParcel) {
                    assertEquals("$name: the Parcel constructor changed", init.implementation!!.instructions.map { it.opcode },
                        context.mutableClassDefBy(params.type).methods.single { it.name == "<init>" && parameterTypeNames(it) == parameterTypeNames(init) }
                            .implementation!!.instructions.map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        val hook = ExtensionDex.classDef("$EXTENSION_PACKAGE/comments/CommentSheetOptions;").methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == KEYBOARD_ON_FIRST_LOAD
        }
        assertTrue("the extension has no static $KEYBOARD_ON_FIRST_LOAD", hook != null && AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
