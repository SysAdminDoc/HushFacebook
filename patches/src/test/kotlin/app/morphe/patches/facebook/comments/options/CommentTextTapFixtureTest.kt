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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post text doesn't open comments (#594) on every declared build: one launcher opens the comment
 * sheet from a Context and the popover params, the params hold one Integer, and after the patch the
 * launcher asks the extension about that Integer before anything of its own runs. Read from 588
 * (2026-11-26): the launcher is `LX/09YS;->A00`, the field `A03`. Neither name is used here.
 */
class CommentTextTapFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun MethodReference.smali() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /** The hook's eight instructions, in order, ahead of the launcher's own first one. */
    private val hookOpcodes = listOf(
        Opcode.MOVE_OBJECT_FROM16, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT,
    )

    @Test
    fun `each declared build's comment sheet launcher asks the extension first`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val launchers = FixtureDex.methodsWhere(bundle, { dex ->
                    dex.typeSection.any { it.toString() == POPOVER_FRAGMENT }
                }) { isPopoverLauncher(it) }
                assertEquals("$name: comment sheet launchers", 1, launchers.size)
                val launcher = launchers.single()
                val classes = FixtureDex.classes(bundle, setOf(POPOVER_PARAMS, launcher.definingClass))
                val params = classes[POPOVER_PARAMS]
                assertNotNull("$name: no $POPOVER_PARAMS", params)
                val field = flyoutContextField(params!!)
                assertNotNull("$name: the params hold no one Integer", field)
                val host = classes.getValue(launcher.definingClass)
                val stock = launcher.implementation!!.instructions.toList()

                // Negative control: params with no Integer give no field, and the launcher stays as it was.
                val bare = ImmutableClassDef(
                    params.type, params.accessFlags, params.superclass, params.interfaces, params.sourceFile,
                    params.annotations, params.fields.filter { it.type != "Ljava/lang/Integer;" }, params.methods.toList(),
                )
                assertNull("$name: a flyout context found in params without one", flyoutContextField(bare))
                val untouched = PatchContexts.of(listOf(bare, host))
                untouched.keepCommentsClosedOnTextTap("test")
                assertEquals("$name: the patch changed a launcher it couldn't read the params of", stock.map { it.opcode },
                    untouched.mutableClassDefBy(host.type).methods.single { isPopoverLauncher(it) }
                        .implementation!!.instructions.map { it.opcode })

                val context = PatchContexts.of(listOf(params, host))
                context.keepCommentsClosedOnTextTap("test")
                val patched = context.mutableClassDefBy(host.type).methods.single { isPopoverLauncher(it) }
                    .implementation!!.instructions.toList()
                assertEquals("$name: the hook's instructions", hookOpcodes, patched.take(hookOpcodes.size).map { it.opcode })
                val read = (patched[2] as ReferenceInstruction).reference as FieldReference
                assertEquals("$name: the hook reads another field", "${field!!.definingClass}->${field.name}:${field.type}",
                    "${read.definingClass}->${read.name}:${read.type}")
                assertEquals("$name: the hook calls something else", KEEPS_COMMENTS_CLOSED,
                    ((patched[3] as ReferenceInstruction).reference as MethodReference).smali())
                for (branch in listOf(1, 5)) {
                    assertEquals("$name: the hook's branch at $branch skips somewhere else", hookOpcodes.size,
                        (patched[branch] as BuilderOffsetInstruction).target.location.index)
                }
                assertEquals("$name: the rest of the launcher is Facebook's", stock.map { it.opcode },
                    patched.drop(hookOpcodes.size).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        val hook = ExtensionDex.classDef("$EXTENSION_PACKAGE/comments/CommentSheetOptions;").methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == KEEPS_COMMENTS_CLOSED
        }
        assertTrue("the extension has no static $KEEPS_COMMENTS_CLOSED", hook != null && AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
