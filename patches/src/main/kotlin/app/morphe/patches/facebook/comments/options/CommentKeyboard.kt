/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Keep the keyboard closed when comments open (#594). Read from 588, 2026-11-25, arm64; the
 * obfuscated names here are for reviewers, the code never writes one down.
 *
 * Comments open from a [FEEDBACK_PARAMS], a kept class the comment fragment reads as it loads. One
 * boolean of it, the one its toString writes after [KEYBOARD_LABEL] (588 `A0x`), tells the fragment
 * to focus the comment box and raise the keyboard as the comments first load (588 `LX/09YI;->A0P`),
 * and a dozen other readers (the comment fragment's own checks, the surfaces that open it) go by
 * the same field. The params are built in two constructors: one from their builder (588
 * `<init>(LX/084R;)V`), which copies the flag from the builder, and one from a Parcel, which reads
 * back what the first wrote. Every other place that wants new params, the fragment rebuilding its
 * own among them, goes through the builder. So right before the builder's constructor writes the
 * flag, the extension is handed the value and its answer is written instead.
 */

internal const val FEEDBACK_PARAMS = "Lcom/facebook/ufiservices/flyout/params/FeedbackParams;"

/** What the params' toString writes ahead of the keyboard flag. */
internal const val KEYBOARD_LABEL = ", showKeyboardOnFirstLoad="

internal const val KEYBOARD_ON_FIRST_LOAD = "$EXTENSION_PACKAGE/comments/CommentSheetOptions;->keyboardOnFirstLoad(Z)Z"

private const val PARCEL = "Landroid/os/Parcel;"

/** The instance field reads, any width. */
private val FIELD_READS = setOf(
    Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN,
    Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT,
)

/**
 * The keyboard flag: the first field of [params] its toString reads after [KEYBOARD_LABEL], when
 * that's a boolean of the class's own. Null when there's no such label or the read after it is
 * anything else.
 */
internal fun keyboardField(params: ClassDef): FieldReference? {
    val toString = params.methods.singleOrNull {
        it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: return null
    val code = toString.implementation?.instructions?.toList() ?: return null
    val label = code.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == KEYBOARD_LABEL
    }
    if (label < 0) return null
    val read = code.drop(label + 1).firstOrNull { it.opcode in FIELD_READS } ?: return null
    if (read.opcode != Opcode.IGET_BOOLEAN) return null
    return ((read as ReferenceInstruction).reference as FieldReference)
        .takeIf { it.definingClass == params.type && it.type == "Z" }
}

/** The one write of the keyboard flag in a constructor not reading a Parcel: where, and the register written. */
internal class KeyboardWrite(val init: Method, val index: Int, val register: Int)

/**
 * [method]'s parameter types as plain strings. A class read through ImmutableClassDef hands them
 * over as parameter objects, which a String never equals, so `in` and `==` on the raw list miss.
 */
internal fun parameterTypeNames(method: Method): List<String> = method.parameterTypes.map { it.toString() }

/** The register `this` sits in, in [method]'s frame. */
private fun thisRegister(method: Method): Int =
    method.implementation!!.registerCount - 1 - parameterTypeNames(method).sumOf { if (it == "J" || it == "D") 2 else 1 }

/** The [KeyboardWrite] of [field] in [params], or null with why in [why]. */
internal fun keyboardWrite(params: ClassDef, field: FieldReference, why: (String) -> Unit): KeyboardWrite? {
    val writes = params.methods.filter { it.name == "<init>" && PARCEL !in parameterTypeNames(it) }.flatMap { init ->
        init.implementation?.instructions?.toList().orEmpty().withIndex().filter { (_, instruction) ->
            instruction.opcode == Opcode.IPUT_BOOLEAN &&
                ((instruction as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == field.definingClass && it.name == field.name && it.type == field.type
                }
        }.map { (index, instruction) -> Triple(init, index, instruction as TwoRegisterInstruction) }
    }
    val (init, index, put) = writes.singleOrNull()
        ?: return null.also { why("expected one write of ${field.name} in a constructor not from a Parcel, found ${writes.size}") }
    if (put.registerB != thisRegister(init)) return null.also { why("${field.name} is written on another object than the one built") }
    if (put.registerA > 255) return null.also { why("${field.name} is written from v${put.registerA}, past what a result can go to") }
    return KeyboardWrite(init, index, put.registerA)
}

/** Right before the params' builder constructor writes the keyboard flag, the extension picks the value. */
internal fun BytecodePatchContext.keepCommentKeyboardClosed(patch: String) {
    fun skip(why: String) = patchLog.warning("$patch: $why. Keep the keyboard closed when comments open does nothing in this build")
    val params = classDefByOrNull(FEEDBACK_PARAMS) ?: return skip("this build has no $FEEDBACK_PARAMS")
    val field = keyboardField(params) ?: return skip("$FEEDBACK_PARAMS's toString reads no boolean after \"$KEYBOARD_LABEL\"")
    val write = keyboardWrite(params, field, ::skip) ?: return
    val register = write.register
    // At the write's own label, so a branch landing on the write runs the hook as well.
    mutableClassDefBy(params.type).findMutableMethodOf(write.init).addInstructionsAtControlFlowLabel(
        write.index,
        """
            invoke-static/range { v$register .. v$register }, $KEYBOARD_ON_FIRST_LOAD
            move-result v$register
        """,
    )
}
